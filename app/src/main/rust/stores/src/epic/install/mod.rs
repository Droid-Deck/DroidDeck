pub mod cache;
pub mod delta;
pub mod schedule;
pub mod space;
pub mod staging;
pub mod writer;

use std::collections::{HashMap, HashSet};
use std::fs;
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, Ordering};

use serde_json::{json, Value};

use super::build_info::{self, BuildInfo};
use super::locale;
use super::manifest::{self, FileEntry, Manifest};
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
    pub target_manifest_path: Option<PathBuf>,
    pub scratch_dir: Option<PathBuf>,
    pub storage_label: String,
    pub tags: Vec<String>,
    pub previous_tags: Vec<String>,
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
        let tags: Vec<String> = v
            .get("tags")
            .and_then(|t| t.as_array())
            .map(|a| {
                a.iter()
                    .filter_map(|t| t.as_str())
                    .map(str::to_string)
                    .collect()
            })
            .unwrap_or_default();
        let previous_tags = v
            .get("previousTags")
            .and_then(|t| t.as_array())
            .map(|a| a.iter().filter_map(|t| t.as_str()).map(str::to_string).collect())
            .unwrap_or_else(|| tags.clone());
        Ok(Request {
            info,
            game_dir: PathBuf::from(game_dir),
            target_manifest_path: match net::str_of(v, "targetManifestPath") {
                "" => None,
                path => Some(PathBuf::from(path)),
            },
            scratch_dir: (!scratch.is_empty()).then(|| PathBuf::from(scratch)),
            storage_label: Some(net::str_of(v, "storageLabel")).filter(|s| !s.is_empty()).unwrap_or("storage").to_string(),
            tags,
            previous_tags,
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
        && path != ".droiddeck-maintenance"
        && !path.starts_with(".droiddeck-maintenance/")
        && path
            .split('/')
            .all(|seg| !seg.is_empty() && seg != "." && seg != "..")
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

fn installed_manifest_path(game_dir: &Path) -> PathBuf {
    game_dir.join(".droiddeck-maintenance/epic/installed.manifest")
}

fn read_installed_manifest(game_dir: &Path, log: &dyn Fn(&str)) -> Option<Manifest> {
    let path = installed_manifest_path(game_dir);
    match fs::symlink_metadata(&path) {
        Ok(meta) if meta.is_file() && !meta.file_type().is_symlink() => {}
        Ok(_) => {
            log("obsolete Epic files preserved: retained manifest is not a regular file");
            return None;
        }
        Err(_) => return None,
    }
    match fs::read(path).ok().and_then(|bytes| manifest::parse(&bytes).ok()) {
        Some(manifest) => Some(manifest),
        None => {
            log("obsolete Epic files preserved: retained manifest is unavailable or invalid");
            None
        }
    }
}

fn read_target_manifest(req: &Request) -> Result<Option<Vec<u8>>, String> {
    let Some(path) = &req.target_manifest_path else {
        return Ok(None);
    };
    let root = req
        .game_dir
        .canonicalize()
        .map_err(|e| format!("Could not resolve the install folder: {e}"))?;
    if fs::symlink_metadata(path)
        .map(|m| m.file_type().is_symlink())
        .unwrap_or(false)
    {
        return Err("The pinned Epic manifest is a symbolic link".into());
    }
    let candidate = path
        .canonicalize()
        .map_err(|e| format!("The pinned Epic manifest is unavailable: {e}"))?;
    if !candidate.starts_with(&root) || !candidate.is_file() {
        return Err("The pinned Epic manifest is outside the install folder".into());
    }
    fs::read(candidate)
        .map(Some)
        .map_err(|e| format!("Could not read the pinned Epic manifest: {e}"))
}

fn write_installed_manifest(game_dir: &Path, bytes: &[u8]) -> Result<(), String> {
    let metadata = game_dir.join(".droiddeck-maintenance");
    if fs::symlink_metadata(&metadata)
        .map(|m| m.file_type().is_symlink())
        .unwrap_or(false)
    {
        return Err("Epic maintenance metadata is a symbolic link".into());
    }
    let dir = metadata.join("epic");
    if fs::symlink_metadata(&dir)
        .map(|m| m.file_type().is_symlink())
        .unwrap_or(false)
    {
        return Err("Epic maintenance metadata is a symbolic link".into());
    }
    fs::create_dir_all(&dir)
        .map_err(|e| format!("Could not save the Epic install manifest: {e}"))?;
    let temp = dir.join("installed.manifest.tmp");
    if fs::symlink_metadata(&temp)
        .map(|m| m.file_type().is_symlink())
        .unwrap_or(false)
    {
        return Err("Epic maintenance metadata is a symbolic link".into());
    }
    fs::write(&temp, bytes)
        .map_err(|e| format!("Could not save the Epic install manifest: {e}"))?;
    fs::rename(&temp, installed_manifest_path(game_dir))
        .map_err(|e| format!("Could not save the Epic install manifest: {e}"))
}

fn cleanup_obsolete(
    game_dir: &Path,
    old: &[FileEntry],
    old_tags: &[String],
    new: &[FileEntry],
    new_tags: &[String],
    cancel: &AtomicBool,
    log: &dyn Fn(&str),
) -> bool {
    let old_selected = locale::select(old, Some(old_tags));
    let new_selected = locale::select(new, Some(new_tags));
    let key = |path: &str| -> Option<String> {
        if !safe_relative(path) {
            return None;
        }
        Some(path.replace('\\', "/").to_lowercase())
    };
    let inventory = |files: &[FileEntry], indices: &[usize]| -> Option<HashSet<String>> {
        let mut out = HashSet::new();
        for &i in indices {
            if !out.insert(key(&files[i].path)?) {
                return None;
            }
        }
        Some(out)
    };
    let Some(old_keys) = inventory(old, &old_selected) else {
        log("obsolete Epic files preserved: old inventory contains unsafe or case-colliding paths");
        return cancel.load(Ordering::Relaxed);
    };
    let Some(new_keys) = inventory(new, &new_selected) else {
        log("obsolete Epic files preserved: target inventory contains unsafe or case-colliding paths");
        return cancel.load(Ordering::Relaxed);
    };
    let _ = old_keys;
    let Ok(root) = game_dir.canonicalize() else {
        log("obsolete Epic files preserved: install folder could not be resolved");
        return cancel.load(Ordering::Relaxed);
    };
    let mut removed = 0usize;
    let mut preserved = 0usize;
    for i in old_selected {
        if cancel.load(Ordering::Relaxed) {
            log("obsolete Epic cleanup cancelled; remaining files were preserved");
            return true;
        }
        let file = &old[i];
        let Some(k) = key(&file.path) else { preserved += 1; continue };
        if new_keys.contains(&k) { continue; }
        let Some(expected) = file.sha1 else { preserved += 1; continue };
        let mut path = game_dir.to_path_buf();
        let mut safe = true;
        for segment in file.path.split('/') {
            path.push(segment);
            if fs::symlink_metadata(&path).map(|m| m.file_type().is_symlink()).unwrap_or(false) {
                safe = false;
                break;
            }
        }
        let Ok(metadata) = fs::symlink_metadata(&path) else { continue };
        let Ok(canonical) = path.canonicalize() else { preserved += 1; continue };
        if !safe || !metadata.is_file() || !canonical.starts_with(&root) {
            preserved += 1;
            continue;
        }
        if !matches!(delta::check(&canonical, file.size, Some(expected), cancel), delta::Check::UpToDate) {
            preserved += 1;
            continue;
        }
        if fs::remove_file(&canonical).is_ok() { removed += 1; } else { preserved += 1; }
    }
    log(&format!("obsolete Epic cleanup removed={removed} preserved={preserved}"));
    cancel.load(Ordering::Relaxed)
}

fn resume_from(stage: &Path, parts: &[PlanPart], size: u64) -> Option<HashSet<u64>> {
    let meta = fs::symlink_metadata(stage).ok()?;
    if !meta.is_file() || meta.len() > size {
        return None;
    }
    let list = fs::read_to_string(staging::list_path(stage)).ok()?;
    let kept: HashSet<u64> = list
        .lines()
        .map(|l| l.parse().ok())
        .collect::<Option<_>>()?;
    let fits = |o: &u64| {
        parts
            .iter()
            .any(|p| p.file_offset == *o && p.file_offset + p.size as u64 <= meta.len())
    };
    (!kept.is_empty() && kept.iter().all(fits)).then_some(kept)
}

pub fn run(req: &Request, cancel: &AtomicBool, ev: &dyn Events) -> Result<Finished, Stop> {
    let failed = |m: String| Stop::Failed(m);
    ev.status("Downloading the manifest…");
    let log = |line: &str| ev.log(line);
    let pinned_manifest = read_target_manifest(req).map_err(failed)?;
    let manifest_bytes = match pinned_manifest {
        Some(bytes) => bytes,
        None => build_info::download_manifest(&req.info, &req.info.cdns, &log)
            .ok_or_else(|| failed("The manifest could not be downloaded".into()))?,
    };
    let manifest = manifest::parse(&manifest_bytes).map_err(|e| {
        ev.log(&format!("manifest parse: {e}"));
        failed("The manifest could not be parsed".into())
    })?;
    let installed_manifest = read_installed_manifest(&req.game_dir, &log);
    cancelled(cancel)?;
    ev.status("Reading the manifest…");

    let selected = locale::select(&manifest.files, Some(&req.tags));
    for &i in &selected {
        let path = &manifest.files[i].path;
        if !safe_relative(path) {
            return Err(failed(format!("The manifest has an unsafe path: {path}")));
        }
        if staging::conflicts(path) {
            return Err(failed(
                "The manifest conflicts with the staging folder".into(),
            ));
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
    let mut partials = staging::partials(&stage_dir);

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
        for stale in partials.values() {
            staging::remove_partial(stale);
        }
        ev.log("every selected file is already up to date");
        clean_caches();
        ev.installing(disk_bytes, disk_bytes, total_selected, total_selected);
        let cleanup_cancelled = if let Some(installed) = installed_manifest.as_ref() {
            cleanup_obsolete(
                &req.game_dir,
                &installed.files,
                &req.previous_tags,
                &manifest.files,
                &req.tags,
                cancel,
                &log,
            )
        } else {
            ev.log("obsolete Epic files preserved: no retained prior manifest is available");
            cancel.load(Ordering::Relaxed)
        };
        if cleanup_cancelled || cancel.load(Ordering::Relaxed) {
            return Err(Stop::Cancelled);
        }
        write_installed_manifest(&req.game_dir, &manifest_bytes).map_err(failed)?;
        return Ok(finished);
    }

    let mut plan_files = Vec::with_capacity(pending.len());
    let mut engine_files = Vec::with_capacity(pending.len());
    let mut resumed_bytes = 0u64;
    let mut kept_sizes = Vec::with_capacity(pending.len());
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
            parts.push(PlanPart {
                chunk: c as u32,
                chunk_offset: part.offset,
                size: part.size,
                file_offset: offset,
            });
            offset += part.size as u64;
        }
        let mut resume = None;
        let mut kept_size = 0u64;
        if let Some(stage) = partials.remove(&staging::partial_name(&f.path, f.size, f.sha1)) {
            match resume_from(&stage, &parts, f.size).filter(|_| f.sha1.is_some()) {
                Some(kept) => {
                    let bytes: u64 = parts.iter().filter(|p| kept.contains(&p.file_offset)).map(|p| p.size as u64).sum();
                    ev.log(&format!("resuming {} with {bytes} of {} bytes written", f.path, f.size));
                    resumed_bytes += bytes;
                    kept_size = bytes;
                    parts.retain(|p| !kept.contains(&p.file_offset));
                    resume = Some(writer::Resume {
                        stage,
                        kept: kept.into_iter().collect(),
                    });
                }
                None => staging::remove_partial(&stage),
            }
        }
        kept_sizes.push(kept_size);
        plan_files.push(PlanFile {
            size: f.size,
            parts: parts.clone(),
        });
        engine_files.push(writer::EngineFile {
            path: f.path.clone(),
            dest: req.game_dir.join(&f.path),
            size: f.size,
            sha1: f.sha1,
            replacing: old_size > 0,
            parts,
            resume,
        });
    }
    for stale in partials.values() {
        staging::remove_partial(stale);
    }
    let fresh = pending.iter().all(|&(_, old_size)| old_size == 0);
    let progress_base = resumed_bytes + if fresh { disk_bytes - pending_bytes } else { 0 };

    let scratch = req
        .scratch_dir
        .clone()
        .filter(|_| !dir_has_entries(&beside))
        .filter(|s| !space::same_volume(s, &req.game_dir));
    let sizes: Vec<space::PendingSize> = pending
        .iter()
        .zip(&kept_sizes)
        .map(|(&(i, old), kept)| space::PendingSize {
            new_size: manifest.files[i].size - kept,
            old_size: old,
        })
        .collect();
    let free_game = space::free_bytes(&req.game_dir);
    let scratch_space = scratch.as_ref().map(|s| space::Scratch {
        free: space::free_bytes(s),
    });
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
    let mut reused = cache::prepare(
        &cache_dir,
        &manifest.chunks,
        &first_use,
        plan.cache_budget,
        cancel,
    );
    if let Some(installed) = installed_manifest.as_ref() {
        let needed_chunks: HashSet<u32> = first_use.keys().copied().collect();
        let cached_bytes: u64 = reused
            .iter()
            .map(|i| manifest.chunks[*i as usize].window as u64)
            .sum();
        let remaining_budget = plan.cache_budget.saturating_sub(cached_bytes);
        let (seeded, _) = cache::seed_from_verified_installed(
            &cache_dir,
            &req.game_dir,
            installed,
            &manifest,
            &needed_chunks,
            &reused,
            remaining_budget,
            cancel,
        );
        if !seeded.is_empty() {
            reused = cache::prepare(
                &cache_dir,
                &manifest.chunks,
                &first_use,
                plan.cache_budget,
                cancel,
            );
            let retained: Vec<u32> = seeded.into_iter().filter(|i| reused.contains(i)).collect();
            let bytes: u64 = retained
                .iter()
                .map(|i| manifest.chunks[*i as usize].window as u64)
                .sum();
            if !retained.is_empty() {
                ev.log(&format!(
                    "reused {} verified installed-file chunks ({bytes} bytes)",
                    retained.len()
                ));
            }
        }
    }
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
        progress_base,
        progress_total: progress_base + pending_bytes - resumed_bytes,
    };
    let stats = writer::run(&input, cancel, ev)?;
    finished.bytes_written = stats.bytes_written;
    finished.files_written = stats.files_written;
    clean_caches();
    ev.installing(disk_bytes, disk_bytes, total_selected, total_selected);
    let cleanup_cancelled = if let Some(installed) = installed_manifest.as_ref() {
        cleanup_obsolete(
            &req.game_dir,
            &installed.files,
            &req.previous_tags,
            &manifest.files,
            &req.tags,
            cancel,
            &log,
        )
    } else {
        ev.log("obsolete Epic files preserved: no retained prior manifest is available");
        cancel.load(Ordering::Relaxed)
    };
    if cleanup_cancelled || cancel.load(Ordering::Relaxed) {
        return Err(Stop::Cancelled);
    }
    write_installed_manifest(&req.game_dir, &manifest_bytes).map_err(failed)?;
    Ok(finished)
}

#[cfg(test)]
mod tests;
