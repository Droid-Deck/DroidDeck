// SPDX-License-Identifier: GPL-3.0-or-later
//! Installs or updates an EA game from its build archive: files whose size and CRC already match
//! are kept, everything else is fetched entry by entry with ranged reads and inflated in place.

use std::collections::{HashMap, HashSet};
use std::fs::{self, File, OpenOptions};
use std::io::{Read, Write};
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, AtomicU64, AtomicUsize, Ordering};
use std::sync::Mutex;
use std::time::{Duration, Instant, UNIX_EPOCH};

use flate2::{Crc, Decompress, FlushDecompress, Status};
use serde_json::{json, Value};

use super::net;
use super::zip::{self, Entry};
use crate::amazon::install::check::{free_bytes, space_problem};
use crate::amazon::install::{Events, ReplaceGate, Stop};
use crate::fetch_core::{distinct_host_count, run_fetch, FetchItem, FetchOptions, FetchSink, SinkError};

pub const LEDGER: &str = ".droiddeck-ea-files";
pub const IN_PROGRESS_MARKER: &str = ".droiddeck-ea-downloading";
const TEMP_SUFFIX: &str = ".ddpart";
const PROGRESS_EVERY_MS: u64 = 50;
const CHECK_REPORT_EVERY: Duration = Duration::from_millis(200);
const REQUEST_TIMEOUT: Duration = Duration::from_secs(120);
const INFLATE_BUFFER: usize = 256 * 1024;
pub const REPLACE_REFUSED: &str = "The install record could not be switched to updating; nothing was replaced";

pub struct Request {
    pub url: String,
    pub game_dir: PathBuf,
    pub max_workers: usize,
    pub process_workers: usize,
    pub ca_bundle: String,
}

impl Request {
    pub fn from_json(v: &Value) -> Result<Request, String> {
        let text = |k: &str| v.get(k).and_then(|x| x.as_str()).unwrap_or("").to_string();
        let game_dir = text("gameDir");
        if game_dir.is_empty() || !game_dir.starts_with('/') {
            return Err("No install folder was given".into());
        }
        let url = text("url");
        if !url.starts_with("https://") && !url.starts_with("http://") {
            return Err("EA gave no download for this game".into());
        }
        Ok(Request {
            url,
            game_dir: PathBuf::from(game_dir),
            max_workers: v.get("maxWorkers").and_then(|n| n.as_u64()).unwrap_or(8) as usize,
            process_workers: v.get("processWorkers").and_then(|n| n.as_u64()).unwrap_or(2) as usize,
            ca_bundle: text("caBundle"),
        })
    }
}

#[derive(Debug, Default)]
pub struct Finished {
    pub total_bytes: u64,
    pub download_bytes: u64,
    pub file_count: usize,
    pub files_written: usize,
    pub bytes_written: u64,
    pub removed: usize,
}

impl Finished {
    pub fn to_json(&self) -> Value {
        json!({
            "totalBytes": self.total_bytes,
            "downloadBytes": self.download_bytes,
            "fileCount": self.file_count,
            "filesWritten": self.files_written,
            "bytesWritten": self.bytes_written,
            "removed": self.removed,
        })
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
struct Known {
    crc: u32,
    size: u64,
    modified: u64,
}

fn modified_ms(path: &Path) -> u64 {
    fs::metadata(path)
        .and_then(|m| m.modified())
        .ok()
        .and_then(|t| t.duration_since(UNIX_EPOCH).ok())
        .map(|d| d.as_millis() as u64)
        .unwrap_or(0)
}

fn read_ledger(dir: &Path) -> HashMap<String, Known> {
    let Ok(text) = fs::read_to_string(dir.join(LEDGER)) else { return HashMap::new() };
    let mut out = HashMap::new();
    for line in text.lines() {
        let mut parts = line.splitn(4, '\t');
        let (Some(crc), Some(size), Some(modified), Some(path)) = (parts.next(), parts.next(), parts.next(), parts.next()) else {
            continue;
        };
        let (Ok(crc), Ok(size), Ok(modified)) = (u32::from_str_radix(crc, 16), size.parse(), modified.parse()) else {
            continue;
        };
        out.insert(path.to_string(), Known { crc, size, modified });
    }
    out
}

fn ledger_line(path: &str, k: Known) -> String {
    format!("{:08x}\t{}\t{}\t{path}\n", k.crc, k.size, k.modified)
}

fn crc_of(path: &Path, cancel: &AtomicBool) -> Result<Option<u32>, ()> {
    let Ok(mut f) = File::open(path) else { return Ok(None) };
    let mut crc = Crc::new();
    let mut buf = vec![0u8; 1024 * 1024];
    loop {
        if cancel.load(Ordering::Relaxed) {
            return Err(());
        }
        match f.read(&mut buf) {
            Ok(0) => return Ok(Some(crc.sum())),
            Ok(n) => crc.update(&buf[..n]),
            Err(e) if e.kind() == std::io::ErrorKind::Interrupted => {}
            Err(_) => return Ok(None),
        }
    }
}

fn temp_path(dest: &Path) -> PathBuf {
    let mut name = dest.as_os_str().to_owned();
    name.push(TEMP_SUFFIX);
    PathBuf::from(name)
}

pub struct Planned {
    pub entry: Entry,
    pub path: String,
    pub range_end: u64,
}

/// Every file entry with its safe path and the last byte its local record can occupy.
pub fn plan(entries: &[Entry], directory_offset: u64) -> Result<Vec<Planned>, String> {
    let mut offsets: Vec<u64> = entries.iter().map(|e| e.local_offset).collect();
    offsets.push(directory_offset);
    offsets.sort_unstable();
    offsets.dedup();
    let mut seen = HashSet::new();
    let mut out = Vec::new();
    for e in entries.iter().filter(|e| !e.is_dir()) {
        if e.method != zip::STORED && e.method != zip::DEFLATED {
            return Err(format!("{} uses an unsupported compression method ({})", e.name, e.method));
        }
        let path = zip::safe_path(&e.name).ok_or_else(|| format!("The archive names a file outside the game folder: {}", e.name))?;
        if !seen.insert(path.to_lowercase()) {
            continue;
        }
        let next = offsets.iter().copied().find(|&o| o > e.local_offset).unwrap_or(directory_offset);
        let minimum = e.local_offset + zip::LOCAL_FIXED as u64 + e.compressed;
        if next < minimum {
            return Err(format!("{} overlaps the next record in the archive", e.name));
        }
        out.push(Planned { entry: e.clone(), path, range_end: next - 1 });
    }
    Ok(out)
}

struct Slot {
    header: Vec<u8>,
    payload_from: Option<usize>,
    consumed: u64,
    file: File,
    tmp: PathBuf,
    crc: Crc,
    written: u64,
    inflate: Option<Decompress>,
    buffer: Vec<u8>,
}

struct Placer<'a> {
    jobs: &'a [&'a Planned],
    game_dir: &'a Path,
    slots: Vec<Mutex<Option<Slot>>>,
    placed: Vec<AtomicBool>,
    ledger: Mutex<Option<File>>,
    events: &'a dyn Events,
    gate: &'a ReplaceGate,
    stop: &'a AtomicBool,
    failure: Mutex<Option<String>>,
    base_done: u64,
    base_files: u32,
    total: u64,
    total_files: u32,
    placed_bytes: AtomicU64,
    in_flight: AtomicU64,
    shown: AtomicU64,
    bytes_written: AtomicU64,
    files_placed: AtomicUsize,
    last_emit: AtomicU64,
    started: Instant,
}

fn discard(slot: Slot) {
    drop(slot.file);
    let _ = fs::remove_file(&slot.tmp);
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
        let value = self.base_done + self.placed_bytes.load(Ordering::Relaxed) + self.in_flight.load(Ordering::Relaxed);
        let shown = self.shown.fetch_max(value, Ordering::Relaxed).max(value);
        self.events.progress(
            shown.min(self.total),
            self.total,
            self.base_files + self.files_placed.load(Ordering::Relaxed) as u32,
            self.total_files,
        );
    }

    fn open(&self, job: &Planned) -> Result<Slot, String> {
        let dest = self.game_dir.join(&job.path);
        if let Some(parent) = dest.parent() {
            fs::create_dir_all(parent).map_err(|e| format!("Could not create the folder for {}: {e}", job.path))?;
        }
        let tmp = temp_path(&dest);
        let file = File::create(&tmp).map_err(|e| format!("Could not write {}: {e}", job.path))?;
        Ok(Slot {
            header: Vec::new(),
            payload_from: None,
            consumed: 0,
            file,
            tmp,
            crc: Crc::new(),
            written: 0,
            inflate: (job.entry.method == zip::DEFLATED).then(|| Decompress::new(false)),
            buffer: Vec::new(),
        })
    }

    fn write_out(&self, job: &Planned, slot: &mut Slot, data: &[u8]) -> Result<(), SinkError> {
        if slot.written + data.len() as u64 > job.entry.size {
            return Err(SinkError::Retry(format!("{}: more data than the archive lists", job.path)));
        }
        slot.file
            .write_all(data)
            .map_err(|e| SinkError::Fatal(self.fail(format!("Could not write {}: {e}", job.path))))?;
        slot.crc.update(data);
        slot.written += data.len() as u64;
        self.bytes_written.fetch_add(data.len() as u64, Ordering::Relaxed);
        Ok(())
    }

    fn feed(&self, job: &Planned, slot: &mut Slot, payload: &[u8], finish: bool) -> Result<(), SinkError> {
        let Some(mut inflate) = slot.inflate.take() else {
            return self.write_out(job, slot, payload);
        };
        let mut out = std::mem::take(&mut slot.buffer);
        out.resize(INFLATE_BUFFER, 0);
        let result = self.inflate_into(job, slot, &mut inflate, &mut out, payload, finish);
        slot.inflate = Some(inflate);
        slot.buffer = out;
        result
    }

    fn inflate_into(&self, job: &Planned, slot: &mut Slot, inflate: &mut Decompress, out: &mut [u8], mut input: &[u8], finish: bool) -> Result<(), SinkError> {
        let mode = if finish { FlushDecompress::Finish } else { FlushDecompress::None };
        loop {
            let (in_before, out_before) = (inflate.total_in(), inflate.total_out());
            let status = inflate
                .decompress(input, out, mode)
                .map_err(|e| SinkError::Retry(format!("{}: the compressed data is damaged ({e})", job.path)))?;
            let used = (inflate.total_in() - in_before) as usize;
            let produced = (inflate.total_out() - out_before) as usize;
            input = &input[used..];
            if produced > 0 {
                self.write_out(job, slot, &out[..produced])?;
            }
            if status == Status::StreamEnd || (used == 0 && produced == 0) {
                return Ok(());
            }
            if input.is_empty() && !finish && produced < out.len() {
                return Ok(());
            }
        }
    }

    fn piece(&self, i: usize, offset: u64, data: &[u8]) -> Result<(), SinkError> {
        let job = self.jobs[i];
        let mut guard = self.slots[i].lock().expect("slot lock");
        if offset == 0 {
            if let Some(old) = guard.take() {
                self.in_flight.fetch_sub(old.consumed, Ordering::Relaxed);
                discard(old);
            }
            *guard = Some(self.open(job).map_err(|e| SinkError::Fatal(self.fail(e)))?);
        }
        let Some(slot) = guard.as_mut() else {
            return Err(SinkError::Retry(format!("{}: data arrived before the start of the record", job.path)));
        };
        if slot.payload_from.is_none() {
            slot.header.extend_from_slice(data);
            match zip::local_header_len(&slot.header) {
                None => return Ok(()),
                Some(Err(e)) => return Err(SinkError::Retry(format!("{}: {e}", job.path))),
                Some(Ok(len)) if slot.header.len() < len => return Ok(()),
                Some(Ok(len)) => {
                    slot.payload_from = Some(len);
                    let header = std::mem::take(&mut slot.header);
                    let rest = header[len..].to_vec();
                    return self.payload(job, slot, &rest);
                }
            }
        }
        self.payload(job, slot, data)
    }

    fn payload(&self, job: &Planned, slot: &mut Slot, data: &[u8]) -> Result<(), SinkError> {
        let left = job.entry.compressed.saturating_sub(slot.consumed);
        let take = &data[..(left.min(data.len() as u64) as usize)];
        if take.is_empty() {
            return Ok(());
        }
        slot.consumed += take.len() as u64;
        self.in_flight.fetch_add(take.len() as u64, Ordering::Relaxed);
        self.feed(job, slot, take, false)?;
        self.emit(false);
        Ok(())
    }

    fn finish(&self, i: usize) -> Result<u64, SinkError> {
        let job = self.jobs[i];
        let taken = self.slots[i].lock().expect("slot lock").take();
        let Some(mut slot) = taken else {
            return Err(SinkError::Retry(format!("{}: the record arrived empty", job.path)));
        };
        self.in_flight.fetch_sub(slot.consumed, Ordering::Relaxed);
        if slot.consumed != job.entry.compressed {
            let got = slot.consumed;
            discard(slot);
            return Err(SinkError::Retry(format!("{}: {got} of {} compressed bytes arrived", job.path, job.entry.compressed)));
        }
        if slot.inflate.is_some() {
            if let Err(e) = self.feed(job, &mut slot, &[], true) {
                discard(slot);
                return Err(e);
            }
        }
        if slot.written != job.entry.size || slot.crc.sum() != job.entry.crc32 {
            let got = slot.written;
            discard(slot);
            return Err(SinkError::Retry(format!("{}: {got} bytes inflated with a CRC mismatch", job.path)));
        }
        let Slot { file, tmp, .. } = slot;
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
            return Err(SinkError::Fatal(self.fail(REPLACE_REFUSED.into())));
        }
        let dest = self.game_dir.join(&job.path);
        if let Err(e) = fs::rename(&tmp, &dest) {
            let _ = fs::remove_file(&tmp);
            return Err(SinkError::Fatal(self.fail(format!("Could not put {} in place: {e}", job.path))));
        }
        self.record(job, &dest);
        self.placed[i].store(true, Ordering::SeqCst);
        self.placed_bytes.fetch_add(job.entry.compressed, Ordering::Relaxed);
        self.files_placed.fetch_add(1, Ordering::Relaxed);
        self.emit(true);
        Ok(0)
    }

    fn record(&self, job: &Planned, dest: &Path) {
        let known = Known { crc: job.entry.crc32, size: job.entry.size, modified: modified_ms(dest) };
        if let Some(file) = self.ledger.lock().expect("ledger lock").as_mut() {
            let _ = file.write_all(ledger_line(&job.path, known).as_bytes());
        }
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

fn origin(url: &str) -> String {
    let after = url.find("://").map(|i| i + 3).unwrap_or(0);
    let end = url[after..].find('/').map(|i| after + i).unwrap_or(url.len());
    url[..end].to_string()
}

fn cancelled(cancel: &AtomicBool) -> Result<(), Stop> {
    if cancel.load(Ordering::SeqCst) {
        Err(Stop::Cancelled)
    } else {
        Ok(())
    }
}

pub fn run(req: &Request, cancel: &AtomicBool, ev: &dyn Events) -> Result<Finished, Stop> {
    let failed = Stop::Failed;
    let started = Instant::now();
    fs::create_dir_all(&req.game_dir).map_err(|e| failed(format!("Could not create the install folder: {e}")))?;
    let _ = File::create(req.game_dir.join(IN_PROGRESS_MARKER));

    ev.status("Reading the build…");
    let archive = net::read_archive(&req.url).map_err(|e| {
        ev.log(&format!("archive: {e}"));
        failed(format!("The EA build could not be read: {e}"))
    })?;
    cancelled(cancel)?;
    let planned = plan(&archive.entries, archive.directory.offset).map_err(failed)?;
    let total_size: u64 = planned.iter().map(|p| p.entry.size).sum();
    let total_compressed: u64 = planned.iter().map(|p| p.entry.compressed).sum();
    ev.log(&format!(
        "archive bytes={} entries={} files={} size={total_size} compressed={total_compressed}",
        archive.total,
        archive.entries.len(),
        planned.len()
    ));

    for p in &planned {
        let _ = fs::remove_file(temp_path(&req.game_dir.join(&p.path)));
    }

    ev.status("Checking files…");
    let mut ledger = read_ledger(&req.game_dir);
    let mut pending: Vec<&Planned> = Vec::new();
    let mut kept: Vec<(String, Known)> = Vec::new();
    let mut checked = 0u64;
    let mut last_report = Instant::now();
    for (n, p) in planned.iter().enumerate() {
        let dest = req.game_dir.join(&p.path);
        let size_ok = fs::symlink_metadata(&dest).map(|m| m.is_file() && m.len() == p.entry.size).unwrap_or(false);
        let mut complete = false;
        if size_ok {
            let modified = modified_ms(&dest);
            let known = ledger.get(&p.path).copied();
            if known == Some(Known { crc: p.entry.crc32, size: p.entry.size, modified }) {
                complete = true;
            } else {
                match crc_of(&dest, cancel) {
                    Err(()) => return Err(Stop::Cancelled),
                    Ok(Some(crc)) if crc == p.entry.crc32 => {
                        complete = true;
                        kept.push((p.path.clone(), Known { crc, size: p.entry.size, modified }));
                    }
                    Ok(_) => {}
                }
            }
        }
        if !complete {
            pending.push(p);
        }
        checked += p.entry.size;
        if last_report.elapsed() >= CHECK_REPORT_EVERY || n + 1 == planned.len() {
            ev.checking(checked, total_size, (n + 1) as u32, planned.len() as u32);
            last_report = Instant::now();
        }
        cancelled(cancel)?;
    }
    for (path, known) in kept {
        ledger.insert(path, known);
    }

    let pending_size: u64 = pending.iter().map(|p| p.entry.size).sum();
    let pending_compressed: u64 = pending.iter().map(|p| p.entry.compressed).sum();
    ev.log(&format!(
        "resume kept_files={} pending_files={} pending_bytes={pending_size} pending_download={pending_compressed}",
        planned.len() - pending.len(),
        pending.len()
    ));
    ev.sizes(pending_size, total_size);
    if let Some(message) = space_problem(pending_size, free_bytes(&req.game_dir)) {
        return Err(failed(message));
    }

    let gate = ReplaceGate::new();
    let (empty, remote): (Vec<&Planned>, Vec<&Planned>) = pending.into_iter().partition(|p| p.entry.size == 0 && p.entry.crc32 == 0);
    for p in &empty {
        let dest = req.game_dir.join(&p.path);
        if let Some(parent) = dest.parent() {
            fs::create_dir_all(parent).map_err(|e| failed(format!("Could not create the folder for {}: {e}", p.path)))?;
        }
        if !gate.allow(ev) {
            return Err(failed(REPLACE_REFUSED.into()));
        }
        File::create(&dest).map_err(|e| failed(format!("Could not write {}: {e}", p.path)))?;
        ledger.insert(p.path.clone(), Known { crc: 0, size: 0, modified: modified_ms(&dest) });
    }

    let mut text = String::new();
    for (path, known) in &ledger {
        text.push_str(&ledger_line(path, *known));
    }
    fs::write(req.game_dir.join(LEDGER), text).map_err(|e| failed(format!("Could not write the install record: {e}")))?;
    let ledger_file = OpenOptions::new().append(true).open(req.game_dir.join(LEDGER)).ok();

    let host = origin(&req.url);
    let placer = Placer {
        jobs: &remote,
        game_dir: &req.game_dir,
        slots: remote.iter().map(|_| Mutex::new(None)).collect(),
        placed: remote.iter().map(|_| AtomicBool::new(false)).collect(),
        ledger: Mutex::new(ledger_file),
        events: ev,
        gate: &gate,
        stop: cancel,
        failure: Mutex::new(None),
        base_done: total_compressed - pending_compressed,
        base_files: (planned.len() - remote.len()) as u32,
        total: total_compressed,
        total_files: planned.len() as u32,
        placed_bytes: AtomicU64::new(0),
        in_flight: AtomicU64::new(0),
        shown: AtomicU64::new(0),
        bytes_written: AtomicU64::new(0),
        files_placed: AtomicUsize::new(0),
        last_emit: AtomicU64::new(0),
        started: Instant::now(),
    };
    let cores = std::thread::available_parallelism().map(|n| n.get()).unwrap_or(2);
    let window = req.max_workers.clamp(1, 64);
    let hosts = vec![host.clone()];
    let options = FetchOptions {
        max_workers: window,
        per_host_cap: window.max(6),
        timeout: REQUEST_TIMEOUT,
        headers: vec![("User-Agent".to_string(), net::user_agent())],
        ca_bundle_path: req.ca_bundle.clone(),
        process_workers: req.process_workers.max(cores / 2).max(1),
        label: "ea".to_string(),
        stream: true,
    };
    let items: Vec<FetchItem> = remote
        .iter()
        .enumerate()
        .map(|(i, p)| FetchItem { id: i as u64, urls: vec![req.url.clone()], reserve: 0, range: Some((p.entry.local_offset, p.range_end)) })
        .collect();
    ev.status("Downloading…");
    ev.log(&format!("engine files={} window={window} hosts={}", remote.len(), distinct_host_count(&hosts)));
    placer.emit(true);
    let log = |line: &str| ev.log(line);
    let outcome = if items.is_empty() {
        None
    } else {
        Some(run_fetch(items, &hosts, &options, &Sink(&placer), cancel, &|_: u64, _: u64| {}, &log))
    };
    for slot in &placer.slots {
        if let Some(active) = slot.lock().expect("slot lock").take() {
            discard(active);
        }
    }
    placer.emit(true);
    let placed_all = placer.placed.iter().all(|p| p.load(Ordering::SeqCst));
    let bytes_written = placer.bytes_written.load(Ordering::Relaxed);
    let files_written = placer.files_placed.load(Ordering::Relaxed) + empty.len();
    let elapsed = started.elapsed().as_secs_f64().max(0.001);
    ev.log(&format!(
        "summary files_written={files_written} bytes_written={bytes_written} elapsed_s={elapsed:.1} avg_mbps={:.1}",
        bytes_written as f64 / 1_048_576.0 / elapsed
    ));
    if let Some(message) = placer.failure.lock().expect("failure lock").take() {
        return Err(Stop::Failed(message));
    }
    if !placed_all {
        if let Some(error) = outcome.as_ref().and_then(|o| o.error.clone()) {
            ev.log(&format!("download failed: {error}"));
            return Err(Stop::Failed("A file failed to download".into()));
        }
        if cancel.load(Ordering::SeqCst) {
            return Err(Stop::Cancelled);
        }
        return Err(Stop::Failed("The download ended before every file was written".into()));
    }

    let wanted: HashSet<&str> = planned.iter().map(|p| p.path.as_str()).collect();
    let mut removed = 0;
    let current = read_ledger(&req.game_dir);
    let mut text = String::new();
    for (path, known) in &current {
        if wanted.contains(path.as_str()) {
            text.push_str(&ledger_line(path, *known));
        } else if zip::safe_path(path).as_deref() == Some(path.as_str()) && fs::remove_file(req.game_dir.join(path)).is_ok() {
            removed += 1;
        }
    }
    let _ = fs::write(req.game_dir.join(LEDGER), text);
    if removed > 0 {
        ev.log(&format!("removed {removed} files the new build no longer has"));
    }
    let _ = fs::remove_file(req.game_dir.join(IN_PROGRESS_MARKER));
    Ok(Finished {
        total_bytes: total_size,
        download_bytes: total_compressed,
        file_count: planned.len(),
        files_written,
        bytes_written,
        removed,
    })
}

pub fn size(url: &str) -> Result<Value, String> {
    let archive = net::read_archive(url)?;
    let planned = plan(&archive.entries, archive.directory.offset)?;
    Ok(json!({
        "totalBytes": planned.iter().map(|p| p.entry.size).sum::<u64>(),
        "downloadBytes": planned.iter().map(|p| p.entry.compressed).sum::<u64>(),
        "fileCount": planned.len(),
    }))
}

#[cfg(test)]
mod tests;
