pub mod cache;
pub mod delta;
pub mod schedule;
pub mod space;
pub mod staging;
pub mod writer;

use std::collections::HashMap;
use std::fs;
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, Ordering};

use serde_json::{json, Value};

use super::build_info::{self, BuildInfo};
use super::locale;
use super::manifest::{self, Manifest};
use super::net;
use schedule::{PlanFile, PlanPart};

pub const BESIDE_CACHE_DIR: &str = ".chunks";
const CHECK_PROGRESS_EVERY: usize = 16;

pub trait Events: Sync {
    fn log(&self, line: &str);
    fn status(&self, text: &str);
    fn checking(&self, done_bytes: u64, total_bytes: u64, files: u32, files_total: u32);
    fn sizes(&self, pending_bytes: u64, disk_bytes: u64);
    fn progress(&self, written: u64, total: u64, files: u32, files_total: u32);
    fn installing(&self, done: u64, total: u64, files: u32, files_total: u32);
    fn before_replace(&self) -> bool;
}

#[derive(Debug, PartialEq, Eq)]
pub enum Stop {
    Cancelled,
    Failed(String),
}

pub struct Request {
    pub info: BuildInfo,
    pub game_dir: PathBuf,
    pub scratch_dir: Option<PathBuf>,
    pub storage_label: String,
    pub tags: Vec<String>,
    pub max_workers: usize,
    pub process_workers: usize,
    pub ca_bundle: String,
}

impl Request {
    pub fn from_json(v: &Value) -> Result<Request, String> {
        let info = v.get("info").and_then(BuildInfo::from_json).ok_or("The manifest could not be fetched")?;
        let game_dir = net::str_of(v, "gameDir");
        if game_dir.is_empty() || !game_dir.starts_with('/') {
            return Err("No install folder was given".into());
        }
        let scratch = net::str_of(v, "scratchDir");
        let tags = v
            .get("tags")
            .and_then(|t| t.as_array())
            .map(|a| a.iter().filter_map(|t| t.as_str()).map(str::to_string).collect())
            .unwrap_or_default();
        Ok(Request {
            info,
            game_dir: PathBuf::from(game_dir),
            scratch_dir: (!scratch.is_empty()).then(|| PathBuf::from(scratch)),
            storage_label: Some(net::str_of(v, "storageLabel")).filter(|s| !s.is_empty()).unwrap_or("storage").to_string(),
            tags,
            max_workers: v.get("maxWorkers").and_then(|n| n.as_u64()).unwrap_or(32) as usize,
            process_workers: v.get("processWorkers").and_then(|n| n.as_u64()).unwrap_or(2) as usize,
            ca_bundle: net::str_of(v, "caBundle").to_string(),
        })
    }
}

#[derive(Debug, Default)]
pub struct Finished {
    pub build_version: String,
    pub launch_exe: String,
    pub prereq_name: String,
    pub prereq_path: String,
    pub prereq_read: bool,
    pub deployment_id: String,
    pub disk_bytes: u64,
    pub bytes_written: u64,
    pub files_written: usize,
}

impl Finished {
    pub fn to_json(&self) -> Value {
        json!({
            "buildVersion": self.build_version,
            "launchExe": self.launch_exe,
            "prereqName": self.prereq_name,
            "prereqPath": self.prereq_path,
            "prereqRead": self.prereq_read,
            "deploymentId": self.deployment_id,
            "diskBytes": self.disk_bytes,
            "bytesWritten": self.bytes_written,
            "filesWritten": self.files_written,
        })
    }
}

pub fn safe_relative(path: &str) -> bool {
    !path.is_empty()
        && !path.starts_with('/')
        && path.split('/').all(|seg| !seg.is_empty() && seg != "." && seg != "..")
}

fn dir_has_entries(dir: &Path) -> bool {
    fs::read_dir(dir).map(|mut e| e.next().is_some()).unwrap_or(false)
}

fn remove_tree(dir: &Path) {
    if fs::symlink_metadata(dir).map(|m| m.file_type().is_symlink()).unwrap_or(false) {
        let _ = fs::remove_file(dir);
    } else {
        let _ = fs::remove_dir_all(dir);
    }
}

fn cancelled(cancel: &AtomicBool) -> Result<(), Stop> {
    if cancel.load(Ordering::SeqCst) {
        Err(Stop::Cancelled)
    } else {
        Ok(())
    }
}

pub fn fetch_manifest(info: &BuildInfo, log: &dyn Fn(&str)) -> Result<Manifest, String> {
    if info.cdns.is_empty() {
        return Err("No CDN in the manifest answer".into());
    }
    let bytes = build_info::download_manifest(info, &info.cdns, log).ok_or("The manifest could not be downloaded")?;
    manifest::parse(&bytes).map_err(|e| {
        log(&format!("manifest parse: {e}"));
        "The manifest could not be parsed".to_string()
    })
}

pub fn run(req: &Request, cancel: &AtomicBool, ev: &dyn Events) -> Result<Finished, Stop> {
    let failed = |m: String| Stop::Failed(m);
    ev.status("Downloading the manifest…");
    let log = |line: &str| ev.log(line);
    let manifest = fetch_manifest(&req.info, &log).map_err(failed)?;
    cancelled(cancel)?;
    ev.status("Reading the manifest…");

    let selected = locale::select(&manifest.files, Some(&req.tags));
    for &i in &selected {
        let path = &manifest.files[i].path;
        if !safe_relative(path) {
            return Err(failed(format!("The manifest has an unsafe path: {path}")));
        }
        if staging::conflicts(path) {
            return Err(failed("The manifest conflicts with the staging folder".into()));
        }
    }
    let disk_bytes: u64 = selected.iter().map(|&i| manifest.files[i].size).sum();
    ev.log(&format!(
        "manifest build={} version={} chunk_dir={} files={} selected={} tags={} disk_bytes={disk_bytes}",
        manifest.build_version,
        manifest.version,
        manifest.chunk_folder(),
        manifest.files.len(),
        selected.len(),
        req.tags.join("|"),
    ));

    let stage_dir = staging::recover(&req.game_dir).map_err(failed)?;
    cancelled(cancel)?;

    ev.status("Checking files…");
    let total_selected = selected.len() as u32;
    let mut pending: Vec<(usize, u64)> = Vec::new();
    let mut checked_bytes = 0u64;
    for (n, &i) in selected.iter().enumerate() {
        let f = &manifest.files[i];
        match delta::check(&req.game_dir.join(&f.path), f.size, f.sha1, cancel) {
            delta::Check::Cancelled => return Err(Stop::Cancelled),
            delta::Check::UpToDate => {}
            delta::Check::Pending { old_size } => pending.push((i, old_size)),
        }
        checked_bytes += f.size;
        if (n + 1) % CHECK_PROGRESS_EVERY == 0 || n + 1 == selected.len() {
            ev.checking(checked_bytes, disk_bytes, (n + 1) as u32, total_selected);
        }
        cancelled(cancel)?;
    }
    let pending_bytes: u64 = pending.iter().map(|&(i, _)| manifest.files[i].size).sum();
    ev.sizes(pending_bytes, disk_bytes);

    let mut finished = Finished {
        build_version: manifest.build_version.clone(),
        launch_exe: manifest.launch_exe.clone(),
        prereq_name: manifest.prereq_name.clone(),
        prereq_path: manifest.prereq_path.clone(),
        prereq_read: manifest.prereq_read,
        deployment_id: req.info.deployment_id.clone(),
        disk_bytes,
        ..Default::default()
    };
    let beside = req.game_dir.join(BESIDE_CACHE_DIR);
    let clean_caches = || {
        remove_tree(&beside);
        if let Some(s) = &req.scratch_dir {
            remove_tree(s);
        }
        staging::remove_if_idle(&stage_dir);
    };
    if pending.is_empty() {
        ev.log("every selected file is already up to date");
        clean_caches();
        ev.installing(disk_bytes, disk_bytes, total_selected, total_selected);
        return Ok(finished);
    }

    let mut plan_files = Vec::with_capacity(pending.len());
    let mut engine_files = Vec::with_capacity(pending.len());
    for &(i, old_size) in &pending {
        let f = &manifest.files[i];
        let mut parts = Vec::with_capacity(f.parts.len());
        let mut offset = 0u64;
        for part in &f.parts {
            let Some(&c) = manifest.chunk_index.get(&part.guid) else {
                return Err(failed(format!("The manifest is missing chunk data for {}", f.path)));
            };
            let info = &manifest.chunks[c];
            if info.window_exact && part.offset as u64 + part.size as u64 > info.window as u64 {
                return Err(failed(format!("A chunk is too short for {}", f.path)));
            }
            parts.push(PlanPart { chunk: c as u32, chunk_offset: part.offset, size: part.size, file_offset: offset });
            offset += part.size as u64;
        }
        plan_files.push(PlanFile { size: f.size, parts: parts.clone() });
        engine_files.push(writer::EngineFile {
            path: f.path.clone(),
            dest: req.game_dir.join(&f.path),
            size: f.size,
            sha1: f.sha1,
            replacing: old_size > 0,
            parts,
        });
    }

    let scratch = req
        .scratch_dir
        .clone()
        .filter(|_| !dir_has_entries(&beside))
        .filter(|s| !space::same_volume(s, &req.game_dir));
    let sizes: Vec<space::PendingSize> = pending
        .iter()
        .map(|&(i, old)| space::PendingSize { new_size: manifest.files[i].size, old_size: old })
        .collect();
    let free_game = space::free_bytes(&req.game_dir);
    let scratch_space = scratch.as_ref().map(|s| space::Scratch { free: space::free_bytes(s) });
    let plan = space::plan(&sizes, free_game, scratch_space, &req.storage_label).map_err(failed)?;
    let cache_dir = match (&scratch, plan.cache_on_scratch) {
        (Some(s), true) => s.clone(),
        _ => beside.clone(),
    };
    cancelled(cancel)?;

    let mut first_use: HashMap<u32, u32> = HashMap::new();
    let mut position = 0u32;
    for f in &plan_files {
        for p in &f.parts {
            first_use.entry(p.chunk).or_insert(position);
            position += 1;
        }
    }
    let reused = cache::prepare(&cache_dir, &manifest.chunks, &first_use, plan.cache_budget, cancel);
    cancelled(cancel)?;
    let windows: Vec<u32> = manifest.chunks.iter().map(|c| c.window).collect();
    let schedule = schedule::build(&plan_files, &windows, plan.cache_budget, &reused);

    let prefixes = build_info::chunk_prefixes(&req.info.cdns);
    if prefixes.is_empty() {
        return Err(failed("No CDN in the manifest answer".into()));
    }
    ev.log(&format!(
        "plan mode={} pending_files={} pending_bytes={pending_bytes} fetches={} reused={} cache={} \
cache_budget_mib={} overlap_mib={} free_game={free_game} workers={} process_workers={}{}",
        if plan.narrow { "narrow" } else { "adaptive" },
        pending.len(),
        schedule.fetch.len(),
        reused.len(),
        if plan.cache_on_scratch { "scratch" } else { "beside-game" },
        plan.cache_budget / space::MIB,
        plan.overlap.map(|o| (o / space::MIB).to_string()).unwrap_or_else(|| "unlimited".into()),
        req.max_workers,
        req.process_workers,
        if plan.cache_moved_beside_game { " note=internal-storage-full" } else { "" },
    ));

    let input = writer::EngineInput {
        manifest: &manifest,
        files: engine_files,
        schedule,
        stage_dir: stage_dir.clone(),
        cache_dir,
        prefixes,
        overlap: plan.overlap,
        narrow: plan.narrow,
        max_workers: req.max_workers,
        process_workers: req.process_workers,
        ca_bundle: req.ca_bundle.clone(),
        user_agent: net::user_agent(),
    };
    let stats = writer::run(&input, cancel, ev)?;
    finished.bytes_written = stats.bytes_written;
    finished.files_written = stats.files_written;
    clean_caches();
    ev.installing(disk_bytes, disk_bytes, total_selected, total_selected);
    Ok(finished)
}

#[cfg(test)]
mod tests;
