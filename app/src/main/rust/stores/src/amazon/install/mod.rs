pub mod check;
pub mod writer;

use std::fs;
use std::path::PathBuf;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Mutex;
use std::time::{Duration, Instant};

use serde_json::{json, Value};
use sha2::{Digest, Sha256};

use super::distribution::{self, Download};
use super::manifest::{self, GameFile};
use super::{cdn, net};

pub const IN_PROGRESS_MARKER: &str = ".amazon_download_in_progress";
pub const COMPLETE_MARKER: &str = ".amazon_download_complete";

pub const NOT_SIGNED_IN: &str = "Not signed in to Amazon Games";
pub const NO_DOWNLOAD: &str = "Amazon gave no download for this game";
pub const NO_MANIFEST: &str = "The manifest could not be downloaded";
pub const FILE_FAILED: &str = "A file failed to download";
pub const NO_VERIFIABLE_HASH: &str =
    "Amazon's manifest uses a file hash that DroidDeck cannot verify";
pub const REPLACE_REFUSED: &str =
    "The install record could not be switched to updating; nothing was replaced";

const CHECK_REPORT_EVERY: Duration = Duration::from_millis(200);

pub trait Events: Sync {
    fn log(&self, line: &str);
    fn status(&self, text: &str);
    fn checking(&self, done_bytes: u64, total_bytes: u64, files: u32, files_total: u32);
    fn sizes(&self, pending_bytes: u64, total_bytes: u64);
    fn progress(&self, done: u64, total: u64, files: u32, files_total: u32);
    fn before_replace(&self) -> bool;
}

#[derive(Debug, PartialEq, Eq)]
pub enum Stop {
    Cancelled,
    Failed(String),
}

pub struct ReplaceGate(Mutex<Option<bool>>);

impl ReplaceGate {
    pub fn new() -> Self {
        ReplaceGate(Mutex::new(None))
    }

    pub fn allow(&self, events: &dyn Events) -> bool {
        let mut answer = self.0.lock().expect("gate lock");
        if answer.is_none() {
            *answer = Some(events.before_replace());
        }
        *answer == Some(true)
    }
}

impl Default for ReplaceGate {
    fn default() -> Self {
        Self::new()
    }
}

pub struct Request {
    pub access_token: String,
    pub entitlement_id: String,
    pub game_dir: PathBuf,
    pub target_manifest_path: Option<PathBuf>,
    pub target_version_id: Option<String>,
    pub max_workers: usize,
    pub process_workers: usize,
    pub ca_bundle: String,
}

impl Request {
    pub fn from_json(v: &Value) -> Result<Request, String> {
        let game_dir = net::str_of(v, "gameDir");
        if game_dir.is_empty() || !game_dir.starts_with('/') {
            return Err("No install folder was given".into());
        }
        let access_token = net::str_of(v, "accessToken");
        if access_token.is_empty() {
            return Err(NOT_SIGNED_IN.into());
        }
        let entitlement_id = net::str_of(v, "entitlementId");
        if entitlement_id.is_empty() {
            return Err(NO_DOWNLOAD.into());
        }
        Ok(Request {
            access_token: access_token.to_string(),
            entitlement_id: entitlement_id.to_string(),
            game_dir: PathBuf::from(game_dir),
            target_manifest_path: match net::str_of(v, "targetManifestPath") {
                "" => None,
                path => Some(PathBuf::from(path)),
            },
            target_version_id: match net::str_of(v, "targetVersionId") {
                "" => None,
                version => Some(version.to_string()),
            },
            max_workers: v.get("maxWorkers").and_then(|n| n.as_u64()).unwrap_or(8) as usize,
            process_workers: v.get("processWorkers").and_then(|n| n.as_u64()).unwrap_or(2) as usize,
            ca_bundle: net::str_of(v, "caBundle").to_string(),
        })
    }
}

#[derive(Debug, Default)]
pub struct Finished {
    pub version_id: String,
    pub total_bytes: u64,
    pub file_count: usize,
    pub files_written: usize,
    pub bytes_written: u64,
}

impl Finished {
    pub fn to_json(&self) -> Value {
        json!({
            "versionId": self.version_id,
            "totalBytes": self.total_bytes,
            "fileCount": self.file_count,
            "filesWritten": self.files_written,
            "bytesWritten": self.bytes_written,
        })
    }
}

fn cancelled(cancel: &AtomicBool) -> Result<(), Stop> {
    if cancel.load(Ordering::SeqCst) {
        Err(Stop::Cancelled)
    } else {
        Ok(())
    }
}

pub fn load_manifest(
    download: &Download,
    access_token: &str,
    log: &dyn Fn(&str),
) -> Result<manifest::Manifest, String> {
    let bytes = load_manifest_bytes(download, access_token, log)?;
    manifest::parse(&bytes).map_err(|e| {
        log(&format!("manifest parse: {e}"));
        if e.starts_with("The manifest") {
            e
        } else {
            format!("The manifest could not be read: {e}")
        }
    })
}

fn load_manifest_bytes(
    download: &Download,
    access_token: &str,
    log: &dyn Fn(&str),
) -> Result<Vec<u8>, String> {
    cdn::fetch_manifest(&download.base_url, access_token).map_err(|e| {
        log(&format!("manifest download: {e}"));
        NO_MANIFEST.to_string()
    })
}

fn metadata_dir(game_dir: &PathBuf) -> PathBuf {
    game_dir.join(".droiddeck-maintenance/amazon")
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
        return Err("The pinned Amazon manifest is a symbolic link".into());
    }
    let candidate = path
        .canonicalize()
        .map_err(|e| format!("The pinned Amazon manifest is unavailable: {e}"))?;
    if !candidate.starts_with(&root) || !candidate.is_file() {
        return Err("The pinned Amazon manifest is outside the install folder".into());
    }
    fs::read(candidate)
        .map(Some)
        .map_err(|e| format!("Could not read the pinned Amazon manifest: {e}"))
}

fn write_installed_manifest(game_dir: &PathBuf, bytes: &[u8]) -> Result<(), String> {
    let metadata = game_dir.join(".droiddeck-maintenance");
    if fs::symlink_metadata(&metadata)
        .map(|m| m.file_type().is_symlink())
        .unwrap_or(false)
    {
        return Err("Amazon maintenance metadata is a symbolic link".into());
    }
    let dir = metadata_dir(game_dir);
    if fs::symlink_metadata(&dir)
        .map(|m| m.file_type().is_symlink())
        .unwrap_or(false)
    {
        return Err("Amazon maintenance metadata is a symbolic link".into());
    }
    fs::create_dir_all(&dir)
        .map_err(|e| format!("Could not save the Amazon install manifest: {e}"))?;
    let temp = dir.join("installed.manifest.tmp");
    if fs::symlink_metadata(&temp)
        .map(|m| m.file_type().is_symlink())
        .unwrap_or(false)
    {
        return Err("Amazon maintenance metadata is a symbolic link".into());
    }
    fs::write(&temp, bytes)
        .map_err(|e| format!("Could not save the Amazon install manifest: {e}"))?;
    fs::rename(&temp, dir.join("installed.manifest"))
        .map_err(|e| format!("Could not save the Amazon install manifest: {e}"))
}

fn read_installed_manifest(game_dir: &PathBuf, log: &dyn Fn(&str)) -> Option<manifest::Manifest> {
    let path = metadata_dir(game_dir).join("installed.manifest");
    match fs::symlink_metadata(&path) {
        Ok(meta) if meta.is_file() && !meta.file_type().is_symlink() => {}
        Ok(_) => {
            log("obsolete Amazon files preserved: retained manifest is not a regular file");
            return None;
        }
        Err(_) => return None,
    }
    match fs::read(path).ok().and_then(|bytes| manifest::parse(&bytes).ok()) {
        Some(manifest) => Some(manifest),
        None => {
            log("obsolete Amazon files preserved: retained manifest is unavailable or invalid");
            None
        }
    }
}

fn cleanup_obsolete(
    game_dir: &PathBuf,
    old: &[GameFile],
    new: &[GameFile],
    cancel: &AtomicBool,
    gate: &ReplaceGate,
    ev: &dyn Events,
    log: &dyn Fn(&str),
) -> Result<bool, Stop> {
    let key = |path: &str| -> Option<String> {
        let normalized = manifest::check_path(path).ok()?;
        Some(normalized.to_lowercase())
    };
    let inventory = |files: &[GameFile]| -> Option<std::collections::HashSet<String>> {
        let mut out = std::collections::HashSet::new();
        for file in files {
            if !out.insert(key(&file.path)?) {
                return None;
            }
        }
        Some(out)
    };
    let Some(old_keys) = inventory(old) else {
        log("obsolete Amazon files preserved: old inventory contains unsafe or case-colliding paths");
        return Ok(cancel.load(Ordering::Relaxed));
    };
    let Some(new_keys) = inventory(new) else {
        log("obsolete Amazon files preserved: target inventory contains unsafe or case-colliding paths");
        return Ok(cancel.load(Ordering::Relaxed));
    };
    let _ = old_keys;
    let Ok(root) = game_dir.canonicalize() else {
        log("obsolete Amazon files preserved: install folder could not be resolved");
        return Ok(cancel.load(Ordering::Relaxed));
    };
    let mut removed = 0usize;
    let mut preserved = 0usize;
    for file in old {
        if cancel.load(Ordering::Relaxed) {
            log("obsolete Amazon cleanup cancelled; remaining files were preserved");
            return Ok(true);
        }
        let Some(k) = key(&file.path) else { preserved += 1; continue };
        if new_keys.contains(&k) { continue; }
        if !file.has_supported_hash() && !(file.size == 0 && file.digest.is_empty()) {
            preserved += 1;
            continue;
        }
        let mut path = game_dir.clone();
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
        if check::state(&canonical, file, cancel) != check::State::Complete {
            preserved += 1;
            continue;
        }
        if !gate.allow(ev) {
            return Err(Stop::Failed(REPLACE_REFUSED.into()));
        }
        if cancel.load(Ordering::Relaxed) {
            log("obsolete Amazon cleanup cancelled; remaining files were preserved");
            return Ok(true);
        }
        if fs::remove_file(&canonical).is_ok() { removed += 1; } else { preserved += 1; }
    }
    log(&format!("obsolete Amazon cleanup removed={removed} preserved={preserved}"));
    Ok(cancel.load(Ordering::Relaxed))
}

pub fn run(req: &Request, cancel: &AtomicBool, ev: &dyn Events) -> Result<Finished, Stop> {
    ev.status("Asking Amazon for the download…");
    fs::create_dir_all(&req.game_dir).map_err(|e| Stop::Failed(format!("Could not create the install folder: {e}")))?;
    let _ = fs::File::create(req.game_dir.join(IN_PROGRESS_MARKER));
    let download = distribution::game_download(&req.access_token, &req.entitlement_id).map_err(|f| {
        ev.log(&format!("download lookup: {}", f.message()));
        Stop::Failed(NO_DOWNLOAD.into())
    })?;
    cancelled(cancel)?;
    run_download(req, &download, cancel, ev)
}

fn empty_sha256() -> [u8; 32] {
    Sha256::digest(b"").into()
}

pub fn run_download(
    req: &Request,
    download: &Download,
    cancel: &AtomicBool,
    ev: &dyn Events,
) -> Result<Finished, Stop> {
    let failed = Stop::Failed;
    let started = Instant::now();
    fs::create_dir_all(&req.game_dir).map_err(|e| failed(format!("Could not create the install folder: {e}")))?;
    let _ = fs::File::create(req.game_dir.join(IN_PROGRESS_MARKER));

    ev.status("Downloading manifest…");
    let log = |line: &str| ev.log(line);
    let pinned_bytes = read_target_manifest(req).map_err(failed)?;
    let manifest_bytes = match pinned_bytes {
        Some(bytes) => bytes,
        None => load_manifest_bytes(download, &req.access_token, &log).map_err(failed)?,
    };
    let manifest = manifest::parse(&manifest_bytes).map_err(|e| {
        ev.log(&format!("manifest parse: {e}"));
        failed(format!("The manifest could not be read: {e}"))
    })?;
    let installed_manifest = read_installed_manifest(&req.game_dir, &log);
    cancelled(cancel)?;
    for w in &manifest.warnings {
        ev.log(w);
    }
    let total = manifest.total_size();
    let files_total = manifest.files.len() as u32;
    ev.log(&format!(
        "manifest version={} files={} entries={} total_bytes={total}",
        if download.version_id.is_empty() { "?" } else { download.version_id.as_str() },
        manifest.files.len(),
        manifest.entries,
    ));

    let strays = check::remove_stray_temps(&req.game_dir, &manifest.files);
    if strays > 0 {
        ev.log(&format!("removed {strays} leftover temporary files"));
    }

    ev.status("Checking files…");
    let mut pending: Vec<&GameFile> = Vec::new();
    let mut checked = 0u64;
    let mut last_report = Instant::now();
    for (n, f) in manifest.files.iter().enumerate() {
        match check::state(&req.game_dir.join(&f.path), f, cancel) {
            check::State::Cancelled => return Err(Stop::Cancelled),
            check::State::Complete => {}
            check::State::Pending => pending.push(f),
        }
        checked += f.size;
        if last_report.elapsed() >= CHECK_REPORT_EVERY || n + 1 == manifest.files.len() {
            ev.checking(checked, total, (n + 1) as u32, files_total);
            last_report = Instant::now();
        }
        cancelled(cancel)?;
    }
    let pending_bytes: u64 = pending.iter().map(|f| f.size).sum();
    let skipped_files = manifest.files.len() - pending.len();
    ev.log(&format!("resume skipped_files={skipped_files} skipped_bytes={} pending_files={} pending_bytes={pending_bytes}", total - pending_bytes, pending.len()));
    ev.sizes(pending_bytes, total);

    let free = check::free_bytes(&req.game_dir);
    if let Some(message) = check::space_problem(pending_bytes, free) {
        ev.log(&format!("space pending={pending_bytes} free={free}"));
        return Err(failed(message));
    }

    let gate = ReplaceGate::new();
    let empty = empty_sha256();
    let (local, remote): (Vec<&GameFile>, Vec<&GameFile>) = pending
        .into_iter()
        .partition(|f| f.size == 0 && (f.digest.is_empty() || f.sha256() == Some(empty)));
    if remote.iter().any(|f| !f.has_supported_hash()) {
        return Err(failed(NO_VERIFIABLE_HASH.into()));
    }
    for f in &local {
        let dest = req.game_dir.join(&f.path);
        if let Some(parent) = dest.parent() {
            fs::create_dir_all(parent).map_err(|e| failed(format!("Could not create the folder for {}: {e}", f.path)))?;
        }
        let tmp = check::temp_path(&dest);
        fs::File::create(&tmp).map_err(|e| failed(format!("Could not write {}: {e}", f.path)))?;
        if !gate.allow(ev) {
            let _ = fs::remove_file(&tmp);
            return Err(failed(REPLACE_REFUSED.into()));
        }
        if let Err(e) = fs::rename(&tmp, &dest) {
            let _ = fs::remove_file(&tmp);
            return Err(failed(format!("Could not put {} in place: {e}", f.path)));
        }
    }
    cancelled(cancel)?;

    let jobs: Vec<writer::Job> = remote
        .iter()
        .map(|f| writer::Job {
            path: f.path.clone(),
            dest: req.game_dir.join(&f.path),
            size: f.size,
            algorithm: f.algorithm,
            digest: f.digest.clone(),
            url: cdn::file_url(&download.base_url, &f.cdn_name()),
        })
        .collect();
    let settings = writer::Settings {
        max_workers: req.max_workers,
        process_workers: req.process_workers,
        ca_bundle: req.ca_bundle.clone(),
        user_agent: net::user_agent(),
        hosts: vec![cdn::origin(&download.base_url)],
    };
    let base = writer::Baseline {
        done_bytes: total - pending_bytes,
        done_files: (skipped_files + local.len()) as u32,
        total_bytes: total,
        total_files: files_total,
    };
    let result = writer::run(&jobs, &settings, base, &gate, cancel, ev);
    let elapsed = started.elapsed().as_secs_f64().max(0.001);
    let bytes_written = result.as_ref().map(|s| s.bytes_written).unwrap_or(0);
    ev.log(&format!(
        "summary files={}/{} bytes_written={bytes_written} elapsed_s={elapsed:.1} avg_mbps={:.1} cancelled={} error={}",
        if result.is_ok() { manifest.files.len() } else { skipped_files + local.len() },
        manifest.files.len(),
        bytes_written as f64 / 1_048_576.0 / elapsed,
        matches!(result, Err(Stop::Cancelled)),
        match &result {
            Err(Stop::Failed(m)) => m.as_str(),
            _ => "none",
        },
    ));
    let stats = result?;

    let cleanup_cancelled = if let Some(installed) = installed_manifest.as_ref() {
        cleanup_obsolete(&req.game_dir, &installed.files, &manifest.files, cancel, &gate, ev, &log)?
    } else {
        ev.log("obsolete Amazon files preserved: no retained prior manifest is available");
        cancel.load(Ordering::Relaxed)
    };
    if cleanup_cancelled || cancel.load(Ordering::Relaxed) {
        return Err(Stop::Cancelled);
    }
    let _ = fs::remove_file(req.game_dir.join(IN_PROGRESS_MARKER));
    let _ = fs::File::create(req.game_dir.join(COMPLETE_MARKER));
    write_installed_manifest(&req.game_dir, &manifest_bytes).map_err(failed)?;
    Ok(Finished {
        version_id: req
            .target_version_id
            .as_deref()
            .unwrap_or(&download.version_id)
            .to_string(),
        total_bytes: total,
        file_count: manifest.files.len(),
        files_written: stats.files_written + local.len(),
        bytes_written,
    })
}

pub fn size_of(access_token: &str, entitlement_id: &str) -> u64 {
    let Ok(download) = distribution::game_download(access_token, entitlement_id) else {
        return 0;
    };
    load_manifest(&download, access_token, &|_: &str| {})
        .map(|m| m.total_size())
        .unwrap_or(0)
}

#[cfg(test)]
mod tests;
