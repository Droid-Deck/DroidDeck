//! One `nativeStart` run: plan, take over a half-finished client download, download the game's
//! depots into its library folder, install missing shared depots into their app's folder, then
//! register everything the way the Steam client does. The resume journal lives in the
//! caller-given journal directory and is removed once the game is registered.

use crate::steam::depot_config::{app_journal_dir, DepotConfigStore};
use crate::steam::depot_downloader::{download_prepared, prepare_depot, DepotError, PreparedDepot};
use crate::steam::pb::ccontentserverdirectory::CContentServerDirectoryServerInfo;
use crate::steam::plan::AppPlan;
use crate::steam::register::{self, InstalledDepot, LibraryRoot, SharedInstall, Takeover};
use crate::steam::session::Session;
use serde_json::{json, Value};
use std::path::PathBuf;
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::Mutex;
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

pub const PHASE_PREPARING: &str = "preparing";
pub const PHASE_DOWNLOADING: &str = "downloading";
pub const PHASE_SHARED: &str = "shared";
pub const PHASE_REGISTERING: &str = "registering";
pub const DEFAULT_MAX_WORKERS: usize = 16;
const PROGRESS_INTERVAL: Duration = Duration::from_millis(250);

pub struct StartRequest {
    pub app_id: u32,
    pub language: String,
    pub steam_root: PathBuf,
    pub game_library: LibraryRoot,
    pub libraries: Vec<LibraryRoot>,
    pub journal_dir: PathBuf,
    pub ca_bundle_path: String,
    pub max_workers: usize,
    pub process_workers: usize,
}

/// Decode/write threads when the caller passes 0: half the cores, between 2 and 6.
pub fn default_process_workers() -> usize {
    std::thread::available_parallelism()
        .map(|n| n.get() / 2)
        .unwrap_or(2)
        .clamp(2, 6)
}

pub trait Events: Sync {
    fn progress(&self, done: u64, total: u64, phase: &str);
}

fn fail(error: impl Into<String>) -> Value {
    json!({"ok": false, "error": error.into()})
}

fn now_unix() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_secs())
        .unwrap_or(0)
}

/// The app's plan with shared depots resolved. An app the account does not own is refused,
/// except a free one: `request_free_license` asks Steam for its license first (an install), else
/// it is planned as is (sizes for the UI).
pub fn build_plan(
    session: &Session,
    app_id: u32,
    language: &str,
    request_free_license: bool,
) -> Result<AppPlan, String> {
    let appinfo = session
        .app_info(app_id)
        .ok_or_else(|| "appinfo unavailable".to_string())?;
    let mut library = session.library()?;
    if !library.owns(app_id) {
        if !crate::steam::plan::is_free(&appinfo) {
            return Err("not_owned".to_string());
        }
        if request_free_license {
            if !session.request_free_license(app_id) {
                return Err("free_license_refused".to_string());
            }
            library = session.library()?;
            if !library.owns(app_id) {
                return Err("not_owned".to_string());
            }
        }
    }
    let mut plan = crate::steam::plan::plan_app(app_id, &appinfo, &library.owned_apps, language)?;
    crate::steam::plan::resolve_shared(&mut plan, &|from| session.app_info(from));
    Ok(plan)
}

struct Progress<'a> {
    events: &'a dyn Events,
    total: AtomicU64,
    base: AtomicU64,
    phase: Mutex<&'static str>,
    last: Mutex<Instant>,
}

impl<'a> Progress<'a> {
    fn new(events: &'a dyn Events) -> Self {
        Self {
            events,
            total: AtomicU64::new(0),
            base: AtomicU64::new(0),
            phase: Mutex::new(PHASE_PREPARING),
            last: Mutex::new(Instant::now() - PROGRESS_INTERVAL),
        }
    }

    fn phase(&self, phase: &'static str) {
        *self.phase.lock().expect("phase poisoned") = phase;
        self.emit(self.base.load(Ordering::Relaxed), true);
    }

    fn emit(&self, done: u64, force: bool) {
        {
            let mut last = self.last.lock().expect("progress poisoned");
            if !force && last.elapsed() < PROGRESS_INTERVAL {
                return;
            }
            *last = Instant::now();
        }
        let phase = *self.phase.lock().expect("phase poisoned");
        let total = self.total.load(Ordering::Relaxed);
        self.events.progress(done, total, phase);
    }

    /// Progress inside one depot: `done` bytes of it on top of what earlier depots finished.
    fn depot(&self, done: u64) {
        self.emit(self.base.load(Ordering::Relaxed) + done, false);
    }

    fn finish_depot(&self, bytes: u64) {
        self.base.fetch_add(bytes, Ordering::Relaxed);
        self.emit(self.base.load(Ordering::Relaxed), true);
    }
}

fn servers_for(
    servers: &[CContentServerDirectoryServerInfo],
    app_id: u32,
) -> Vec<CContentServerDirectoryServerInfo> {
    servers
        .iter()
        .filter(|s| s.allowed_app_ids.is_empty() || s.allowed_app_ids.contains(&app_id))
        .cloned()
        .collect()
}

pub fn run(
    session: &Session,
    req: &StartRequest,
    cancel: &AtomicBool,
    events: &dyn Events,
) -> Value {
    let progress = Progress::new(events);
    progress.phase(PHASE_PREPARING);
    let log = |line: &str| crate::steam::log(line);
    let cancelled = || cancel.load(Ordering::Relaxed);

    let plan = match build_plan(session, req.app_id, &req.language, true) {
        Ok(plan) => plan,
        Err(err) => return fail(err),
    };
    if cancelled() {
        return fail("cancelled");
    }
    let takeover = match register::takeover(&req.game_library, req.app_id, plan.build_id) {
        Ok(t) => t,
        Err(err) => return fail(format!("takeover: {err}")),
    };
    log(&format!(
        "start app={} build={} depots={} shared={} takeover={takeover:?}",
        req.app_id,
        plan.build_id,
        plan.depots.len(),
        plan.shared.len()
    ));
    if takeover == Takeover::AlreadyInstalled {
        return json!({"ok": true, "alreadyInstalled": true});
    }
    if takeover == Takeover::Update {
        // Updates stay with the Steam client: it removes files a new build dropped.
        return fail("update_in_steam");
    }
    if !register::library_registered(&req.steam_root, &req.game_library.guest) {
        return fail("library_not_in_libraryfolders");
    }
    let mut journal =
        DepotConfigStore::load(app_journal_dir(&req.journal_dir, req.app_id), plan.build_id);
    if takeover == Takeover::TookOver {
        journal.discard();
    }

    let Some(all_servers) = session.cdn_servers() else {
        return fail("CDN server request failed");
    };
    let game_servers = servers_for(&all_servers, req.app_id);

    let mut game_depots: Vec<(PreparedDepot, u32)> = Vec::new();
    for depot in &plan.depots {
        match prepare_depot(
            session,
            &game_servers,
            Some(&journal),
            &req.ca_bundle_path,
            req.app_id,
            depot.depot_id,
            depot.manifest_id,
            cancel,
        ) {
            Ok(prepared) => game_depots.push((prepared, depot.dlc_app_id)),
            Err(DepotError::AccessDenied(id)) if depot.dlc_app_id != 0 => {
                log(&format!("skipping DLC depot {id}: access denied"));
            }
            Err(err) => return fail(err.message()),
        }
    }

    let mut shared_jobs: Vec<(PreparedDepot, SharedInstall)> = Vec::new();
    let mut shared_present: Vec<(u32, u32)> = Vec::new();
    for shared in &plan.shared {
        let library =
            register::shared_app_library(shared.from_app_id, &req.steam_root, &req.libraries);
        let installed = crate::steam::kv::parse_file(&library.acf_path(shared.from_app_id))
            .and_then(|acf| register::installed_manifest(&acf, shared.depot_id));
        if installed == Some(shared.manifest_id) {
            shared_present.push((shared.depot_id, shared.from_app_id));
            continue;
        }
        let servers = servers_for(&all_servers, shared.from_app_id);
        let prepared = match prepare_depot(
            session,
            &servers,
            Some(&journal),
            &req.ca_bundle_path,
            shared.from_app_id,
            shared.depot_id,
            shared.manifest_id,
            cancel,
        ) {
            Ok(p) => p,
            Err(DepotError::AccessDenied(id)) => {
                log(&format!("skipping shared depot {id}: access denied"));
                continue;
            }
            Err(err) => return fail(err.message()),
        };
        let install = SharedInstall {
            depot_id: shared.depot_id,
            from_app_id: shared.from_app_id,
            from_name: shared.from_name.clone(),
            from_install_dir: shared.from_install_dir.clone(),
            from_build_id: shared.from_build_id,
            manifest_id: shared.manifest_id,
            size: prepared.manifest.metadata.cb_disk_original.max(shared.size),
            install_script: register::install_script_path(&prepared.manifest),
            library,
        };
        shared_jobs.push((prepared, install));
    }

    let depot_bytes =
        |p: &PreparedDepot| crate::steam::depot_writer::depot_total_bytes(&p.manifest);
    let total: u64 = game_depots.iter().map(|(p, _)| depot_bytes(p)).sum::<u64>()
        + shared_jobs.iter().map(|(p, _)| depot_bytes(p)).sum::<u64>();
    progress.total.store(total, Ordering::Relaxed);

    let max_workers = if req.max_workers == 0 {
        DEFAULT_MAX_WORKERS
    } else {
        req.max_workers
    };
    let process_workers = if req.process_workers == 0 {
        default_process_workers()
    } else {
        req.process_workers
    };
    let ca = req.ca_bundle_path.as_str();

    progress.phase(PHASE_DOWNLOADING);
    let game_dir = req.game_library.common_dir(&plan.install_dir);
    for (prepared, _) in &game_depots {
        if journal.is_installed(prepared.depot_id, prepared.manifest_id) {
            progress.finish_depot(depot_bytes(prepared));
            continue;
        }
        if cancelled() {
            return fail("cancelled");
        }
        journal.begin_depot(prepared.depot_id);
        let outcome = download_prepared(
            prepared,
            &game_servers,
            &game_dir,
            ca,
            max_workers,
            process_workers,
            cancel,
            &|done| progress.depot(done),
            &log,
        );
        if outcome.cancelled {
            return fail("cancelled");
        }
        if let Some(err) = outcome.error {
            return fail(format!("depot {}: {err}", prepared.depot_id));
        }
        journal.finish_depot(prepared.depot_id, prepared.manifest_id);
        progress.finish_depot(depot_bytes(prepared));
    }

    progress.phase(PHASE_SHARED);
    for (prepared, install) in &shared_jobs {
        if cancelled() {
            return fail("cancelled");
        }
        let servers = servers_for(&all_servers, install.from_app_id);
        let target = install.library.common_dir(&install.from_install_dir);
        if !journal.is_installed(prepared.depot_id, prepared.manifest_id) {
            journal.begin_depot(prepared.depot_id);
            let outcome = download_prepared(
                prepared,
                &servers,
                &target,
                ca,
                max_workers,
                process_workers,
                cancel,
                &|done| progress.depot(done),
                &log,
            );
            if outcome.cancelled {
                return fail("cancelled");
            }
            if let Some(err) = outcome.error {
                return fail(format!("shared depot {}: {err}", prepared.depot_id));
            }
            journal.finish_depot(prepared.depot_id, prepared.manifest_id);
        }
        progress.finish_depot(depot_bytes(prepared));
    }

    progress.phase(PHASE_REGISTERING);
    let now = now_unix();
    let owner = session.steam_id;
    for (_, install) in &shared_jobs {
        if let Err(err) = register::register_shared_depot(install, owner, now) {
            return fail(format!("register shared depot: {err}"));
        }
        if let Err(err) = register::update_libraryfolders(
            &req.steam_root,
            &install.library.guest,
            install.from_app_id,
            true,
        ) {
            return fail(format!("libraryfolders: {err}"));
        }
    }
    let installed: Vec<InstalledDepot> = game_depots
        .iter()
        .map(|(p, dlc)| InstalledDepot {
            depot_id: p.depot_id,
            manifest_id: p.manifest_id,
            size: p.manifest.metadata.cb_disk_original,
            dlc_app_id: *dlc,
        })
        .collect();
    let scripts: Vec<(u32, String)> = game_depots
        .iter()
        .filter_map(|(p, _)| register::install_script_path(&p.manifest).map(|s| (p.depot_id, s)))
        .collect();
    let mut shared_list: Vec<(u32, u32)> = shared_jobs
        .iter()
        .map(|(_, s)| (s.depot_id, s.from_app_id))
        .collect();
    shared_list.extend(shared_present);
    shared_list.sort_unstable();
    let downloaded = game_depots
        .iter()
        .map(|(p, _)| p.manifest.metadata.cb_disk_compressed)
        .sum::<u64>()
        + shared_jobs
            .iter()
            .map(|(p, _)| p.manifest.metadata.cb_disk_compressed)
            .sum::<u64>();
    let staged = installed.iter().map(|d| d.size).sum::<u64>()
        + shared_jobs.iter().map(|(_, s)| s.size).sum::<u64>();
    let registration = register::GameRegistration {
        plan: &plan,
        owner_steam_id: owner,
        depots: &installed,
        shared: &shared_list,
        install_scripts: &scripts,
        bytes_downloaded: downloaded,
        bytes_staged: staged,
        now,
    };
    if let Err(err) = register::write_game_acf(&req.game_library, &registration) {
        return fail(format!("app manifest: {err}"));
    }
    if let Err(err) =
        register::update_libraryfolders(&req.steam_root, &req.game_library.guest, req.app_id, true)
    {
        return fail(format!("libraryfolders: {err}"));
    }
    journal.discard();
    progress.emit(total, true);
    log(&format!(
        "installed app={} build={} bytes={total} downloaded={downloaded}",
        req.app_id, plan.build_id
    ));
    json!({"ok": true, "alreadyInstalled": false})
}
