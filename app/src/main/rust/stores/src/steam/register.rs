//! What the Steam client writes around an install, so it sees a game we downloaded as installed
//! (and does not update or verify it), and what it removes on uninstall. Modelled on a captured
//! client install and uninstall of Brawlhalla (291550) to an SD-card library:
//!
//! - `<library>/steamapps/appmanifest_<appid>.acf` (key order as the client writes it);
//! - shared depots (e.g. 228988 VC++ 2019 of 228980) in the library holding that app's manifest,
//!   under `steamapps/common/<its installdir>`, recorded in its `InstalledDepots` and
//!   `InstallScripts`, with its `SizeOnDisk` adjusted;
//! - `"<appid>" "0"` under the library's `apps` in both `libraryfolders.vdf` copies (the client
//!   fills in the size later).
//!
//! `config.vdf`, `localconfig.vdf` and `registry.vdf` are never written; the client regenerates
//! them. `depotcache/` is not written either: the client keeps manifests there with decrypted
//! file names, not in the CDN form this engine caches.

use crate::steam::content_manifest::ContentManifest;
use crate::steam::kv;
use crate::steam::plan::AppPlan;
use crate::steam::vdf::KVNode;
use std::collections::HashSet;
use std::fs;
use std::path::{Path, PathBuf};

pub const STATE_UPDATE_REQUIRED: u64 = 2;
pub const STATE_FULLY_INSTALLED: u64 = 4;
/// StateFlags bits that mean "not a finished install": update required, files missing or
/// corrupt, update running/paused/started, uninstalling, reconfiguring, validating, adding files,
/// preallocating, downloading, staging, committing, update stopping.
pub const STATE_UNFINISHED_MASK: u64 = 2
    | 32
    | 128
    | 256
    | 512
    | 1024
    | 2048
    | 65536
    | 131072
    | 262144
    | 524288
    | 1048576
    | 2097152
    | 4194304
    | 8388608;
/// `EDepotFileFlag::InstallScript`.
pub const DEPOT_FILE_FLAG_INSTALL_SCRIPT: u32 = 256;

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct LibraryRoot {
    pub host: PathBuf,
    /// The path as the Steam client sees it inside proot (what `libraryfolders.vdf` holds).
    pub guest: String,
}

impl LibraryRoot {
    pub fn steamapps(&self) -> PathBuf {
        self.host.join("steamapps")
    }

    pub fn acf_path(&self, app_id: u32) -> PathBuf {
        self.steamapps().join(format!("appmanifest_{app_id}.acf"))
    }

    pub fn common_dir(&self, install_dir: &str) -> PathBuf {
        self.steamapps().join("common").join(install_dir)
    }
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub enum Takeover {
    /// No app manifest: a fresh install.
    Fresh,
    /// Fully installed at the build being installed: nothing to do.
    AlreadyInstalled,
    /// Fully installed at another build: files are checked and updated in place, the manifest
    /// is merged.
    Update,
    /// A half-finished client download was removed (its manifest, `downloading/<appid>` and
    /// `temp/<appid>`).
    TookOver,
}

pub fn state_is_finished(flags: u64) -> bool {
    flags & STATE_FULLY_INSTALLED != 0 && flags & STATE_UNFINISHED_MASK == 0
}

pub fn takeover(
    game_library: &LibraryRoot,
    app_id: u32,
    build_id: u64,
) -> Result<Takeover, String> {
    let acf_path = game_library.acf_path(app_id);
    if !acf_path.exists() {
        return Ok(Takeover::Fresh);
    }
    let acf = kv::parse_file(&acf_path);
    if let Some(acf) = acf.as_ref() {
        let flags = kv::get_u64(acf, "StateFlags");
        if state_is_finished(flags) {
            return Ok(if kv::get_u64(acf, "buildid") == build_id {
                Takeover::AlreadyInstalled
            } else {
                Takeover::Update
            });
        }
    }
    let steamapps = game_library.steamapps();
    for dir in [
        steamapps.join("downloading").join(app_id.to_string()),
        steamapps.join("temp").join(app_id.to_string()),
    ] {
        if dir.exists() {
            fs::remove_dir_all(&dir).map_err(|err| format!("remove '{}': {err}", dir.display()))?;
        }
    }
    fs::remove_file(&acf_path).map_err(|err| format!("remove '{}': {err}", acf_path.display()))?;
    Ok(Takeover::TookOver)
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct InstalledDepot {
    pub depot_id: u32,
    pub manifest_id: u64,
    pub size: u64,
    pub dlc_app_id: u32,
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct SharedInstall {
    pub depot_id: u32,
    pub from_app_id: u32,
    pub from_name: String,
    pub from_install_dir: String,
    pub from_build_id: u64,
    pub manifest_id: u64,
    pub size: u64,
    /// Windows-style path of the depot's install script, if it has one.
    pub install_script: Option<String>,
    pub library: LibraryRoot,
}

/// The install script of a depot (`InstallScript` file flag; `installscript.vdf` by name as a
/// fallback), as the Windows-style relative path the client records.
pub fn install_script_path(manifest: &ContentManifest) -> Option<String> {
    let flagged = manifest
        .files
        .iter()
        .find(|f| f.flags & DEPOT_FILE_FLAG_INSTALL_SCRIPT != 0 && f.linktarget.is_empty());
    let named = || {
        manifest.files.iter().find(|f| {
            f.filename
                .rsplit('/')
                .next()
                .is_some_and(|n| n.eq_ignore_ascii_case("installscript.vdf"))
        })
    };
    flagged
        .or_else(named)
        .map(|f| f.filename.replace('/', "\\"))
}

/// Where a shared app lives: the library that already holds its manifest, else the Steam root's
/// own library (its guest path taken from `libraryfolders.vdf` entry `0` when the caller did not
/// list it).
pub fn shared_app_library(
    from_app_id: u32,
    steam_root: &Path,
    libraries: &[LibraryRoot],
) -> LibraryRoot {
    if let Some(lib) = libraries.iter().find(|l| l.acf_path(from_app_id).exists()) {
        return lib.clone();
    }
    if let Some(lib) = libraries.iter().find(|l| same_path(&l.host, steam_root)) {
        return lib.clone();
    }
    let guest = libraryfolders_paths(steam_root)
        .iter()
        .find_map(|path| {
            let root = kv::parse_file(path)?;
            kv::get_str(root.child("0")?, "path")
        })
        .unwrap_or_default();
    LibraryRoot {
        host: steam_root.to_path_buf(),
        guest,
    }
}

fn same_path(a: &Path, b: &Path) -> bool {
    let norm = |p: &Path| p.to_string_lossy().trim_end_matches('/').to_string();
    norm(a) == norm(b)
}

/// The manifest's InstalledDepots entry for `depot_id` (manifest gid), if any.
pub fn installed_manifest(acf: &KVNode, depot_id: u32) -> Option<u64> {
    let entry = acf.child("InstalledDepots")?.child(&depot_id.to_string())?;
    kv::get_str(entry, "manifest")?.parse().ok()
}

pub struct GameRegistration<'a> {
    pub plan: &'a AppPlan,
    pub owner_steam_id: u64,
    pub depots: &'a [InstalledDepot],
    /// Every shared depot the game uses (installed now or already present).
    pub shared: &'a [(u32, u32)],
    pub install_scripts: &'a [(u32, String)],
    pub bytes_downloaded: u64,
    pub bytes_staged: u64,
    pub now: u64,
}

/// Writes (or merges into) `appmanifest_<appid>.acf` in the client's key order.
pub fn write_game_acf(library: &LibraryRoot, reg: &GameRegistration<'_>) -> Result<(), String> {
    let path = library.acf_path(reg.plan.app_id);
    let mut root = kv::parse_file(&path)
        .filter(|r| r.name.eq_ignore_ascii_case("AppState"))
        .unwrap_or_else(|| kv::object("AppState"));
    let size_on_disk: u64 = reg.depots.iter().map(|d| d.size).sum();
    let build = reg.plan.build_id.to_string();
    kv::set(&mut root, "appid", reg.plan.app_id.to_string());
    kv::set(&mut root, "Universe", "1");
    kv::set(&mut root, "name", reg.plan.name.as_str());
    kv::set(&mut root, "StateFlags", "4");
    kv::set(&mut root, "installdir", reg.plan.install_dir.as_str());
    kv::set(&mut root, "LastUpdated", reg.now.to_string());
    set_default(&mut root, "LastPlayed", "0");
    kv::set(&mut root, "SizeOnDisk", size_on_disk.to_string());
    kv::set(&mut root, "StagingSize", "0");
    kv::set(&mut root, "buildid", build.as_str());
    kv::set(&mut root, "LastOwner", reg.owner_steam_id.to_string());
    kv::set(&mut root, "DownloadType", "1");
    kv::set(&mut root, "UpdateResult", "0");
    kv::set(
        &mut root,
        "BytesToDownload",
        reg.bytes_downloaded.to_string(),
    );
    kv::set(
        &mut root,
        "BytesDownloaded",
        reg.bytes_downloaded.to_string(),
    );
    kv::set(&mut root, "BytesToStage", reg.bytes_staged.to_string());
    kv::set(&mut root, "BytesStaged", reg.bytes_staged.to_string());
    kv::set(&mut root, "TargetBuildID", build.as_str());
    set_default(&mut root, "AutoUpdateBehavior", "0");
    set_default(&mut root, "AllowOtherDownloadsWhileRunning", "0");
    set_default(&mut root, "ScheduledAutoUpdate", "0");
    kv::remove(&mut root, "FullValidateAfterNextUpdate");

    let installed = kv::object_mut(&mut root, "InstalledDepots");
    installed.children.clear();
    for depot in reg.depots {
        let mut entry = kv::object(&depot.depot_id.to_string());
        kv::set(&mut entry, "manifest", depot.manifest_id.to_string());
        kv::set(&mut entry, "size", depot.size.to_string());
        if depot.dlc_app_id != 0 {
            kv::set(&mut entry, "dlcappid", depot.dlc_app_id.to_string());
        }
        installed.children.push(entry);
    }
    if reg.install_scripts.is_empty() {
        kv::remove(&mut root, "InstallScripts");
    } else {
        let scripts = kv::object_mut(&mut root, "InstallScripts");
        scripts.children.clear();
        for (depot, path) in reg.install_scripts {
            kv::set(scripts, &depot.to_string(), path.as_str());
        }
    }
    if reg.shared.is_empty() {
        kv::remove(&mut root, "SharedDepots");
    } else {
        let shared = kv::object_mut(&mut root, "SharedDepots");
        shared.children.clear();
        for (depot, from) in reg.shared {
            kv::set(shared, &depot.to_string(), from.to_string());
        }
    }
    kv::set(
        kv::object_mut(&mut root, "UserConfig"),
        "language",
        reg.plan.language.as_str(),
    );
    kv::set(
        kv::object_mut(&mut root, "MountedConfig"),
        "language",
        reg.plan.language.as_str(),
    );
    fs::create_dir_all(library.steamapps()).map_err(|err| format!("mkdir steamapps: {err}"))?;
    kv::write_file(&path, &root)
}

fn set_default(node: &mut KVNode, key: &str, value: &str) {
    if node.child(key).is_none() {
        kv::set(node, key, value);
    }
}

/// Records a shared depot in its app's manifest (creating the manifest when the app has none
/// yet), as the client does: `InstalledDepots`, `InstallScripts`, `SizeOnDisk`.
pub fn register_shared_depot(
    shared: &SharedInstall,
    owner_steam_id: u64,
    now: u64,
) -> Result<bool, String> {
    let path = shared.library.acf_path(shared.from_app_id);
    let existing = kv::parse_file(&path).filter(|r| r.name.eq_ignore_ascii_case("AppState"));
    let created = existing.is_none();
    let mut root = match existing {
        Some(root) => root,
        None => {
            let mut root = kv::object("AppState");
            let build = shared.from_build_id.to_string();
            for (key, value) in [
                ("appid", shared.from_app_id.to_string()),
                ("Universe", "1".to_string()),
                ("name", shared.from_name.clone()),
                ("StateFlags", "4".to_string()),
                ("installdir", shared.from_install_dir.clone()),
                ("LastUpdated", now.to_string()),
                ("LastPlayed", "0".to_string()),
                ("SizeOnDisk", "0".to_string()),
                ("StagingSize", "0".to_string()),
                ("buildid", build.clone()),
                ("LastOwner", owner_steam_id.to_string()),
                ("DownloadType", "1".to_string()),
                ("UpdateResult", "0".to_string()),
                ("BytesToDownload", "0".to_string()),
                ("BytesDownloaded", "0".to_string()),
                ("BytesToStage", "0".to_string()),
                ("BytesStaged", "0".to_string()),
                ("TargetBuildID", build),
                ("AutoUpdateBehavior", "0".to_string()),
                ("AllowOtherDownloadsWhileRunning", "0".to_string()),
                ("ScheduledAutoUpdate", "0".to_string()),
            ] {
                kv::set(&mut root, key, value);
            }
            kv::object_mut(&mut root, "InstalledDepots");
            root
        }
    };
    let depot_key = shared.depot_id.to_string();
    let old_size = root
        .child("InstalledDepots")
        .and_then(|d| d.child(&depot_key))
        .map(|e| kv::get_u64(e, "size"))
        .unwrap_or(0);
    let size_on_disk = kv::get_u64(&root, "SizeOnDisk")
        .saturating_sub(old_size)
        .saturating_add(shared.size);
    kv::set(&mut root, "SizeOnDisk", size_on_disk.to_string());
    {
        let installed = kv::object_mut(&mut root, "InstalledDepots");
        kv::remove(installed, &depot_key);
        let mut entry = kv::object(&depot_key);
        kv::set(&mut entry, "manifest", shared.manifest_id.to_string());
        kv::set(&mut entry, "size", shared.size.to_string());
        installed.children.push(entry);
    }
    if let Some(script) = &shared.install_script {
        let scripts = insert_after(&mut root, "InstallScripts", "InstalledDepots");
        kv::set(scripts, &depot_key, script.as_str());
    }
    if root.child("UserConfig").is_none() {
        kv::object_mut(&mut root, "UserConfig");
    }
    if root.child("MountedConfig").is_none() {
        kv::object_mut(&mut root, "MountedConfig");
    }
    fs::create_dir_all(shared.library.steamapps())
        .map_err(|err| format!("mkdir steamapps: {err}"))?;
    kv::write_file(&path, &root)?;
    Ok(created)
}

/// The object `key`, created right after `after` when missing (the client's section order).
fn insert_after<'a>(root: &'a mut KVNode, key: &str, after: &str) -> &'a mut KVNode {
    if root.child(key).is_none() {
        let pos = root
            .children
            .iter()
            .position(|c| c.name.eq_ignore_ascii_case(after))
            .map(|p| p + 1)
            .unwrap_or(root.children.len());
        root.children.insert(pos, kv::object(key));
    }
    kv::object_mut(root, key)
}

pub fn libraryfolders_paths(steam_root: &Path) -> [PathBuf; 2] {
    [
        steam_root.join("steamapps").join("libraryfolders.vdf"),
        steam_root.join("config").join("libraryfolders.vdf"),
    ]
}

fn normalize_guest(path: &str) -> String {
    let trimmed = path.trim_end_matches('/');
    if trimmed.is_empty() {
        "/".to_string()
    } else {
        trimmed.to_string()
    }
}

/// Does either `libraryfolders.vdf` copy have an entry whose `path` is `guest_path`?
pub fn library_registered(steam_root: &Path, guest_path: &str) -> bool {
    let want = normalize_guest(guest_path);
    libraryfolders_paths(steam_root).iter().any(|path| {
        kv::parse_file(path).is_some_and(|root| {
            root.children.iter().any(|entry| {
                entry.is_object()
                    && kv::get_str(entry, "path").is_some_and(|p| normalize_guest(&p) == want)
            })
        })
    })
}

/// Adds (`add = true`) or removes `"<appid>"` in the `apps` block of the entry whose `path` is
/// `guest_path`, in both copies. Returns how many copies had that entry.
pub fn update_libraryfolders(
    steam_root: &Path,
    guest_path: &str,
    app_id: u32,
    add: bool,
) -> Result<usize, String> {
    let want = normalize_guest(guest_path);
    let mut matched = 0usize;
    for path in libraryfolders_paths(steam_root) {
        let Some(mut root) = kv::parse_file(&path) else {
            continue;
        };
        let app_key = app_id.to_string();
        let mut changed = false;
        let mut found = false;
        for entry in root.children.iter_mut().filter(|c| c.is_object()) {
            let matches = kv::get_str(entry, "path")
                .map(|p| normalize_guest(&p) == want)
                .unwrap_or(false);
            if add {
                if !matches {
                    continue;
                }
                found = true;
                let apps = kv::object_mut(entry, "apps");
                if apps.child(&app_key).is_none() {
                    apps.children.push(kv::string(&app_key, "0"));
                    changed = true;
                }
            } else if let Some(apps) = kv::get_mut(entry, "apps") {
                if matches {
                    found = true;
                }
                if kv::remove(apps, &app_key) {
                    changed = true;
                }
            }
        }
        if found {
            matched += 1;
        }
        if changed {
            kv::write_file(&path, &root)?;
        }
    }
    Ok(matched)
}

fn is_plain_name(name: &str) -> bool {
    !name.is_empty() && name != "." && name != ".." && !name.contains('/') && !name.contains('\\')
}

/// Every app manifest in the libraries, except `skip` (path), parsed.
fn all_app_manifests(libraries: &[LibraryRoot], skip: &[PathBuf]) -> Vec<KVNode> {
    let mut out = Vec::new();
    let mut seen: HashSet<PathBuf> = HashSet::new();
    for lib in libraries {
        let Ok(entries) = fs::read_dir(lib.steamapps()) else {
            continue;
        };
        for entry in entries.flatten() {
            let path = entry.path();
            let name = entry.file_name().to_string_lossy().to_string();
            if !name.starts_with("appmanifest_") || !name.ends_with(".acf") {
                continue;
            }
            if skip.contains(&path) || !seen.insert(path.clone()) {
                continue;
            }
            if let Some(acf) = kv::parse_file(&path) {
                out.push(acf);
            }
        }
    }
    out
}

/// Is `depot_id` listed in the `SharedDepots` of any of these manifests?
pub fn shared_depot_in_use(manifests: &[KVNode], depot_id: u32) -> bool {
    let key = depot_id.to_string();
    manifests.iter().any(|acf| {
        acf.child("SharedDepots")
            .and_then(|s| s.child(&key))
            .is_some()
    })
}

#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct UninstallReport {
    pub removed_shared: Vec<u32>,
    /// Shared depots no other game uses whose file list was not available; left installed.
    pub kept_shared: Vec<u32>,
}

/// Uninstalls like the client: game folder, app manifest, `depotcache` manifests of the game's
/// depots and `steamapps/shadercache/<appid>` under the Steam root go; `compatdata/<appid>`
/// (the Proton prefix, i.e. saves) stays. A shared depot no other installed game lists loses its
/// files (from the depot's file list, `shared_files(app, depot, manifest)`; empty folders stay)
/// and
/// its entries in the shared app's manifest. The app leaves `apps` in both `libraryfolders.vdf`.
pub fn uninstall(
    app_id: u32,
    steam_root: &Path,
    game_library: &LibraryRoot,
    libraries: &[LibraryRoot],
    shared_files: &dyn Fn(u32, u32, u64) -> Option<Vec<String>>,
) -> Result<UninstallReport, String> {
    let acf_path = game_library.acf_path(app_id);
    let acf = kv::parse_file(&acf_path).ok_or_else(|| "not_installed".to_string())?;
    let install_dir = kv::get_str(&acf, "installdir").unwrap_or_default();
    if !is_plain_name(&install_dir) {
        return Err("bad installdir in app manifest".to_string());
    }
    let game_dir = game_library.common_dir(&install_dir);
    if game_dir.exists() {
        fs::remove_dir_all(&game_dir)
            .map_err(|err| format!("remove '{}': {err}", game_dir.display()))?;
    }
    fs::remove_file(&acf_path).map_err(|err| format!("remove '{}': {err}", acf_path.display()))?;

    if let Some(depots) = acf.child("InstalledDepots") {
        for depot in &depots.children {
            let manifest = kv::get_str(depot, "manifest").unwrap_or_default();
            if depot.name.parse::<u32>().is_err() || manifest.parse::<u64>().is_err() {
                continue;
            }
            let cached = steam_root
                .join("depotcache")
                .join(format!("{}_{manifest}.manifest", depot.name));
            let _ = fs::remove_file(cached);
        }
    }
    let shader = steam_root
        .join("steamapps")
        .join("shadercache")
        .join(app_id.to_string());
    if shader.exists() {
        let _ = fs::remove_dir_all(shader);
    }

    let mut report = UninstallReport::default();
    let others = all_app_manifests(libraries, std::slice::from_ref(&acf_path));
    if let Some(shared) = acf.child("SharedDepots") {
        for entry in &shared.children {
            let (Ok(depot_id), Ok(from_app)) = (
                entry.name.parse::<u32>(),
                entry.as_string("").parse::<u32>(),
            ) else {
                continue;
            };
            if shared_depot_in_use(&others, depot_id) {
                continue;
            }
            if remove_shared_depot(depot_id, from_app, libraries, shared_files)? {
                report.removed_shared.push(depot_id);
            } else {
                report.kept_shared.push(depot_id);
            }
        }
    }

    update_libraryfolders(steam_root, &game_library.guest, app_id, false)?;
    Ok(report)
}

/// Removes one shared depot's files and its entries in the owning app's manifest. `false` when
/// the depot is not recorded there or its file list is unknown (nothing changed).
fn remove_shared_depot(
    depot_id: u32,
    from_app: u32,
    libraries: &[LibraryRoot],
    shared_files: &dyn Fn(u32, u32, u64) -> Option<Vec<String>>,
) -> Result<bool, String> {
    let Some(lib) = libraries.iter().find(|l| l.acf_path(from_app).exists()) else {
        return Ok(false);
    };
    let path = lib.acf_path(from_app);
    let Some(mut root) = kv::parse_file(&path) else {
        return Ok(false);
    };
    let Some(manifest_id) = installed_manifest(&root, depot_id) else {
        return Ok(false);
    };
    let install_dir = kv::get_str(&root, "installdir").unwrap_or_default();
    if !is_plain_name(&install_dir) {
        return Ok(false);
    }
    let Some(files) = shared_files(from_app, depot_id, manifest_id) else {
        return Ok(false);
    };
    let base = lib.common_dir(&install_dir);
    for rel in files {
        if !crate::steam::depot_writer::path_is_safe(&rel) {
            continue;
        }
        let target = base.join(&rel);
        if fs::symlink_metadata(&target).is_ok_and(|m| !m.is_dir()) {
            let _ = fs::remove_file(&target);
        }
    }
    let key = depot_id.to_string();
    let size = root
        .child("InstalledDepots")
        .and_then(|d| d.child(&key))
        .map(|e| kv::get_u64(e, "size"))
        .unwrap_or(0);
    let size_on_disk = kv::get_u64(&root, "SizeOnDisk").saturating_sub(size);
    kv::set(&mut root, "SizeOnDisk", size_on_disk.to_string());
    if let Some(installed) = kv::get_mut(&mut root, "InstalledDepots") {
        kv::remove(installed, &key);
    }
    if let Some(scripts) = kv::get_mut(&mut root, "InstallScripts") {
        kv::remove(scripts, &key);
    }
    kv::write_file(&path, &root)?;
    Ok(true)
}

/// Regular files and links of a manifest (relative, `/`-separated), for shared-depot removal.
pub fn manifest_file_list(manifest: &ContentManifest) -> Vec<String> {
    manifest
        .files
        .iter()
        .filter(|f| f.flags & crate::steam::depot_writer::DEPOT_FILE_FLAG_DIRECTORY == 0)
        .map(|f| f.filename.clone())
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::steam::content_manifest::FileMapping;

    const OWNER: u64 = 76561198000000001;

    fn temp_root(name: &str) -> PathBuf {
        let dir = std::env::temp_dir().join(format!(
            "droiddeck_steam_register_{name}_{}",
            std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .unwrap()
                .as_nanos()
        ));
        fs::create_dir_all(&dir).unwrap();
        dir
    }

    const LIBRARYFOLDERS: &str = "\"libraryfolders\"\n{\n\t\"0\"\n\t{\n\t\t\"path\"\t\t\"/root/.local/share/Steam\"\n\t\t\"label\"\t\t\"Internal Storage\"\n\t\t\"contentid\"\t\t\"4468340155036637738\"\n\t\t\"totalsize\"\t\t\"0\"\n\t\t\"update_clean_bytes_tally\"\t\t\"2147547047\"\n\t\t\"time_last_update_verified\"\t\t\"1792656438\"\n\t\t\"apps\"\n\t\t{\n\t\t\t\"228980\"\t\t\"172268093\"\n\t\t}\n\t}\n\t\"1\"\n\t{\n\t\t\"path\"\t\t\"/mnt/droiddeck-sd\"\n\t\t\"label\"\t\t\"SD card\"\n\t\t\"contentid\"\t\t\"-5681848553336386775\"\n\t\t\"totalsize\"\t\t\"989672112128\"\n\t\t\"update_clean_bytes_tally\"\t\t\"0\"\n\t\t\"time_last_update_verified\"\t\t\"0\"\n\t\t\"apps\"\n\t\t{\n\t\t}\n\t}\n\t\"2\"\n\t{\n\t\t\"path\"\t\t\"/root/.droiddeck-x64/shared/x86-64\"\n\t\t\"label\"\t\t\"x86-64 client games\"\n\t\t\"apps\"\n\t\t{\n\t\t\t\"550\"\t\t\"15100020676\"\n\t\t}\n\t}\n}\n";

    const REDIST_ACF: &str = "\"AppState\"\n{\n\t\"appid\"\t\t\"228980\"\n\t\"Universe\"\t\t\"1\"\n\t\"name\"\t\t\"Steamworks Common Redistributables\"\n\t\"StateFlags\"\t\t\"4\"\n\t\"installdir\"\t\t\"Steamworks Shared\"\n\t\"LastUpdated\"\t\t\"1792892508\"\n\t\"LastPlayed\"\t\t\"0\"\n\t\"SizeOnDisk\"\t\t\"143055920\"\n\t\"StagingSize\"\t\t\"0\"\n\t\"buildid\"\t\t\"24098430\"\n\t\"LastOwner\"\t\t\"76561198000000001\"\n\t\"DownloadType\"\t\t\"1\"\n\t\"UpdateResult\"\t\t\"0\"\n\t\"BytesToDownload\"\t\t\"0\"\n\t\"BytesDownloaded\"\t\t\"0\"\n\t\"BytesToStage\"\t\t\"0\"\n\t\"BytesStaged\"\t\t\"0\"\n\t\"TargetBuildID\"\t\t\"24098430\"\n\t\"AutoUpdateBehavior\"\t\t\"0\"\n\t\"AllowOtherDownloadsWhileRunning\"\t\t\"0\"\n\t\"ScheduledAutoUpdate\"\t\t\"0\"\n\t\"InstalledDepots\"\n\t{\n\t\t\"228989\"\n\t\t{\n\t\t\t\"manifest\"\t\t\"5753583882400741046\"\n\t\t\t\"size\"\t\t\"25674515\"\n\t\t}\n\t\t\"229007\"\n\t\t{\n\t\t\t\"manifest\"\t\t\"4477590687906973371\"\n\t\t\t\"size\"\t\t\"117381405\"\n\t\t}\n\t}\n\t\"InstallScripts\"\n\t{\n\t\t\"228989\"\t\t\"_CommonRedist\\\\vcredist\\\\2022\\\\installscript.vdf\"\n\t\t\"229007\"\t\t\"_CommonRedist\\\\DotNet\\\\4.8\\\\installscript.vdf\"\n\t}\n\t\"UserConfig\"\n\t{\n\t}\n\t\"MountedConfig\"\n\t{\n\t}\n}\n";

    /// The captured client acf for Brawlhalla (LastOwner replaced).
    const BRAWLHALLA_ACF: &str = "\"AppState\"\n{\n\t\"appid\"\t\t\"291550\"\n\t\"Universe\"\t\t\"1\"\n\t\"name\"\t\t\"Brawlhalla\"\n\t\"StateFlags\"\t\t\"4\"\n\t\"installdir\"\t\t\"Brawlhalla\"\n\t\"LastUpdated\"\t\t\"1794027736\"\n\t\"LastPlayed\"\t\t\"0\"\n\t\"SizeOnDisk\"\t\t\"1565156559\"\n\t\"StagingSize\"\t\t\"0\"\n\t\"buildid\"\t\t\"26465982\"\n\t\"LastOwner\"\t\t\"76561198000000001\"\n\t\"DownloadType\"\t\t\"1\"\n\t\"UpdateResult\"\t\t\"0\"\n\t\"BytesToDownload\"\t\t\"1551111680\"\n\t\"BytesDownloaded\"\t\t\"1551111680\"\n\t\"BytesToStage\"\t\t\"1594368732\"\n\t\"BytesStaged\"\t\t\"1594368732\"\n\t\"TargetBuildID\"\t\t\"26465982\"\n\t\"AutoUpdateBehavior\"\t\t\"0\"\n\t\"AllowOtherDownloadsWhileRunning\"\t\t\"0\"\n\t\"ScheduledAutoUpdate\"\t\t\"0\"\n\t\"InstalledDepots\"\n\t{\n\t\t\"291551\"\n\t\t{\n\t\t\t\"manifest\"\t\t\"4224536525702420323\"\n\t\t\t\"size\"\t\t\"1565156559\"\n\t\t}\n\t}\n\t\"SharedDepots\"\n\t{\n\t\t\"228988\"\t\t\"228980\"\n\t}\n\t\"UserConfig\"\n\t{\n\t\t\"language\"\t\t\"english\"\n\t}\n\t\"MountedConfig\"\n\t{\n\t\t\"language\"\t\t\"english\"\n\t}\n}\n";

    struct Fixture {
        base: PathBuf,
        steam_root: PathBuf,
        internal: LibraryRoot,
        sd: LibraryRoot,
    }

    impl Fixture {
        fn new(name: &str) -> Self {
            let base = temp_root(name);
            let steam_root = base.join("Steam");
            let sd_host = base.join("sd").join("steam");
            fs::create_dir_all(steam_root.join("steamapps")).unwrap();
            fs::create_dir_all(steam_root.join("config")).unwrap();
            fs::create_dir_all(steam_root.join("depotcache")).unwrap();
            fs::create_dir_all(sd_host.join("steamapps")).unwrap();
            for path in libraryfolders_paths(&steam_root) {
                fs::write(path, LIBRARYFOLDERS).unwrap();
            }
            fs::write(
                steam_root.join("steamapps").join("appmanifest_228980.acf"),
                REDIST_ACF,
            )
            .unwrap();
            Fixture {
                internal: LibraryRoot {
                    host: steam_root.clone(),
                    guest: "/root/.local/share/Steam".into(),
                },
                sd: LibraryRoot {
                    host: sd_host,
                    guest: "/mnt/droiddeck-sd".into(),
                },
                steam_root,
                base,
            }
        }

        fn libraries(&self) -> Vec<LibraryRoot> {
            vec![self.internal.clone(), self.sd.clone()]
        }
    }

    fn brawlhalla_plan() -> AppPlan {
        AppPlan {
            app_id: 291550,
            name: "Brawlhalla".into(),
            install_dir: "Brawlhalla".into(),
            build_id: 26465982,
            language: "english".into(),
            ..Default::default()
        }
    }

    fn vc2019_manifest() -> ContentManifest {
        let file = |name: &str, flags: u32| FileMapping {
            filename: name.into(),
            flags,
            ..Default::default()
        };
        ContentManifest {
            files: vec![
                file("_CommonRedist/vcredist/2019", 64),
                file("_CommonRedist/vcredist/2019/VC_redist.x64.exe", 0),
                file("_CommonRedist/vcredist/2019/VC_redist.x86.exe", 0),
                file("_CommonRedist/vcredist/2019/installscript.vdf", 256),
            ],
            ..Default::default()
        }
    }

    fn register_brawlhalla(fx: &Fixture) {
        let plan = brawlhalla_plan();
        let depots = [InstalledDepot {
            depot_id: 291551,
            manifest_id: 4224536525702420323,
            size: 1565156559,
            dlc_app_id: 0,
        }];
        let shared = SharedInstall {
            depot_id: 228988,
            from_app_id: 228980,
            from_name: "Steamworks Common Redistributables".into(),
            from_install_dir: "Steamworks Shared".into(),
            from_build_id: 24098430,
            manifest_id: 6645201662696499616,
            size: 29212173,
            install_script: install_script_path(&vc2019_manifest()),
            library: shared_app_library(228980, &fx.steam_root, &fx.libraries()),
        };
        assert_eq!(shared.library, fx.internal);
        assert!(!register_shared_depot(&shared, OWNER, 1794027736).unwrap());
        write_game_acf(
            &fx.sd,
            &GameRegistration {
                plan: &plan,
                owner_steam_id: OWNER,
                depots: &depots,
                shared: &[(228988, 228980)],
                install_scripts: &[],
                bytes_downloaded: 1551111680,
                bytes_staged: 1594368732,
                now: 1794027736,
            },
        )
        .unwrap();
        assert!(library_registered(&fx.steam_root, "/mnt/droiddeck-sd/"));
        assert!(!library_registered(&fx.steam_root, "/mnt/elsewhere"));
        assert_eq!(
            update_libraryfolders(&fx.steam_root, &fx.sd.guest, 291550, true).unwrap(),
            2
        );
    }

    #[test]
    fn registration_reproduces_the_captured_client_install() {
        let fx = Fixture::new("install");
        register_brawlhalla(&fx);

        let acf = fs::read_to_string(fx.sd.acf_path(291550)).unwrap();
        assert_eq!(acf, BRAWLHALLA_ACF);

        let redist = kv::parse_file(&fx.internal.acf_path(228980)).unwrap();
        assert_eq!(kv::get_u64(&redist, "SizeOnDisk"), 143055920 + 29212173);
        assert_eq!(
            installed_manifest(&redist, 228988),
            Some(6645201662696499616)
        );
        let text = fs::read_to_string(fx.internal.acf_path(228980)).unwrap();
        assert!(text.contains(
            "\t\t\"228988\"\t\t\"_CommonRedist\\\\vcredist\\\\2019\\\\installscript.vdf\"\n"
        ));
        assert!(text.contains("\t\t\"228988\"\n\t\t{\n\t\t\t\"manifest\"\t\t\"6645201662696499616\"\n\t\t\t\"size\"\t\t\"29212173\"\n\t\t}\n"));
        // Unknown keys and order of the shared manifest are kept.
        assert!(text.starts_with("\"AppState\"\n{\n\t\"appid\"\t\t\"228980\"\n"));

        for path in libraryfolders_paths(&fx.steam_root) {
            let text = fs::read_to_string(path).unwrap();
            assert!(text.contains("\t\t\"path\"\t\t\"/mnt/droiddeck-sd\"\n\t\t\"label\"\t\t\"SD card\"\n\t\t\"contentid\"\t\t\"-5681848553336386775\"\n\t\t\"totalsize\"\t\t\"989672112128\"\n\t\t\"update_clean_bytes_tally\"\t\t\"0\"\n\t\t\"time_last_update_verified\"\t\t\"0\"\n\t\t\"apps\"\n\t\t{\n\t\t\t\"291550\"\t\t\"0\"\n\t\t}\n"));
            assert!(text.contains("\t\t\t\"228980\"\t\t\"172268093\"\n"));
        }
        // Registering twice changes nothing.
        register_brawlhalla(&fx);
        assert_eq!(
            fs::read_to_string(fx.sd.acf_path(291550)).unwrap(),
            BRAWLHALLA_ACF
        );
        let redist = kv::parse_file(&fx.internal.acf_path(228980)).unwrap();
        assert_eq!(kv::get_u64(&redist, "SizeOnDisk"), 143055920 + 29212173);
        let _ = fs::remove_dir_all(&fx.base);
    }

    #[test]
    fn merge_keeps_unknown_keys_and_user_settings() {
        let fx = Fixture::new("merge");
        let path = fx.sd.acf_path(291550);
        fs::write(
            &path,
            "\"AppState\"\n{\n\t\"appid\"\t\t\"291550\"\n\t\"LastPlayed\"\t\t\"1700000000\"\n\t\"AutoUpdateBehavior\"\t\t\"1\"\n\t\"UserConfig\"\n\t{\n\t\t\"BetaKey\"\t\t\"\"\n\t}\n\t\"SomethingNew\"\t\t\"x\"\n}\n",
        )
        .unwrap();
        register_brawlhalla(&fx);
        let acf = kv::parse_file(&path).unwrap();
        assert_eq!(kv::get_str(&acf, "LastPlayed").unwrap(), "1700000000");
        assert_eq!(kv::get_str(&acf, "AutoUpdateBehavior").unwrap(), "1");
        assert_eq!(kv::get_str(&acf, "SomethingNew").unwrap(), "x");
        let user = acf.child("UserConfig").unwrap();
        assert!(user.child("BetaKey").is_some());
        assert_eq!(kv::get_str(user, "language").unwrap(), "english");
        let _ = fs::remove_dir_all(&fx.base);
    }

    #[test]
    fn takeover_removes_a_half_finished_client_download() {
        let fx = Fixture::new("takeover");
        assert_eq!(takeover(&fx.sd, 291550, 26465982).unwrap(), Takeover::Fresh);
        let steamapps = fx.sd.steamapps();
        fs::create_dir_all(steamapps.join("downloading/291550/x")).unwrap();
        fs::create_dir_all(steamapps.join("temp/291550")).unwrap();
        fs::write(
            fx.sd.acf_path(291550),
            "\"AppState\"\n{\n\t\"StateFlags\"\t\t\"1026\"\n\t\"buildid\"\t\t\"0\"\n}\n",
        )
        .unwrap();
        assert_eq!(
            takeover(&fx.sd, 291550, 26465982).unwrap(),
            Takeover::TookOver
        );
        assert!(!fx.sd.acf_path(291550).exists());
        assert!(!steamapps.join("downloading/291550").exists());
        assert!(!steamapps.join("temp/291550").exists());
        fs::write(fx.sd.acf_path(291550), BRAWLHALLA_ACF).unwrap();
        assert_eq!(
            takeover(&fx.sd, 291550, 26465982).unwrap(),
            Takeover::AlreadyInstalled
        );
        assert_eq!(takeover(&fx.sd, 291550, 1).unwrap(), Takeover::Update);
        assert!(state_is_finished(4));
        assert!(!state_is_finished(6));
        assert!(!state_is_finished(1));
        let _ = fs::remove_dir_all(&fx.base);
    }

    #[test]
    fn uninstall_matches_the_captured_client_uninstall() {
        let fx = Fixture::new("uninstall");
        register_brawlhalla(&fx);
        let game_dir = fx.sd.common_dir("Brawlhalla");
        fs::create_dir_all(game_dir.join("bin")).unwrap();
        fs::write(game_dir.join("bin/Brawlhalla.exe"), b"x").unwrap();
        let shared_dir = fx
            .internal
            .common_dir("Steamworks Shared")
            .join("_CommonRedist/vcredist/2019");
        fs::create_dir_all(&shared_dir).unwrap();
        for name in [
            "VC_redist.x64.exe",
            "VC_redist.x86.exe",
            "installscript.vdf",
        ] {
            fs::write(shared_dir.join(name), b"x").unwrap();
        }
        let compat = fx.sd.steamapps().join("compatdata/291550/pfx");
        fs::create_dir_all(&compat).unwrap();
        let shader = fx.steam_root.join("steamapps/shadercache/291550");
        fs::create_dir_all(&shader).unwrap();
        let cached = fx
            .steam_root
            .join("depotcache/291551_4224536525702420323.manifest");
        fs::write(&cached, b"m").unwrap();
        let shared_cached = fx
            .steam_root
            .join("depotcache/228988_6645201662696499616.manifest");
        fs::write(&shared_cached, b"m").unwrap();

        let files = |app: u32, depot: u32, manifest: u64| {
            (app == 228980 && depot == 228988 && manifest == 6645201662696499616)
                .then(|| manifest_file_list(&vc2019_manifest()))
        };
        let report = uninstall(291550, &fx.steam_root, &fx.sd, &fx.libraries(), &files).unwrap();
        assert_eq!(report.removed_shared, vec![228988]);
        assert!(!game_dir.exists());
        assert!(!fx.sd.acf_path(291550).exists());
        assert!(!cached.exists());
        assert!(shared_cached.exists());
        assert!(!shader.exists());
        assert!(compat.exists());
        assert!(shared_dir.exists());
        assert_eq!(fs::read_dir(&shared_dir).unwrap().count(), 0);
        assert_eq!(
            fs::read_to_string(fx.internal.acf_path(228980)).unwrap(),
            REDIST_ACF
        );
        for path in libraryfolders_paths(&fx.steam_root) {
            assert_eq!(fs::read_to_string(path).unwrap(), LIBRARYFOLDERS);
        }
        assert_eq!(
            uninstall(291550, &fx.steam_root, &fx.sd, &fx.libraries(), &files).unwrap_err(),
            "not_installed"
        );
        let _ = fs::remove_dir_all(&fx.base);
    }

    #[test]
    fn shared_depot_used_by_another_game_is_kept() {
        let fx = Fixture::new("shared_in_use");
        register_brawlhalla(&fx);
        let other = BRAWLHALLA_ACF
            .replace("291550", "993090")
            .replace("\"Brawlhalla\"", "\"Lossless Scaling\"");
        fs::write(fx.internal.acf_path(993090), other).unwrap();
        let report = uninstall(
            291550,
            &fx.steam_root,
            &fx.sd,
            &fx.libraries(),
            &|_, _, _| Some(vec!["_CommonRedist/vcredist/2019/VC_redist.x64.exe".into()]),
        )
        .unwrap();
        assert!(report.removed_shared.is_empty());
        assert!(report.kept_shared.is_empty());
        let redist = kv::parse_file(&fx.internal.acf_path(228980)).unwrap();
        assert_eq!(
            installed_manifest(&redist, 228988),
            Some(6645201662696499616)
        );
        let _ = fs::remove_dir_all(&fx.base);
    }

    #[test]
    fn shared_library_defaults_to_the_steam_root() {
        let fx = Fixture::new("shared_default");
        let only_sd = vec![fx.sd.clone()];
        assert_eq!(
            shared_app_library(228980, &fx.steam_root, &only_sd),
            fx.internal
        );
        assert_eq!(
            shared_app_library(1, &fx.steam_root, &fx.libraries()),
            fx.internal
        );
        let _ = fs::remove_dir_all(&fx.base);
    }

    #[test]
    fn new_shared_app_manifest_is_created_when_missing() {
        let fx = Fixture::new("new_shared");
        fs::remove_file(fx.internal.acf_path(228980)).unwrap();
        let shared = SharedInstall {
            depot_id: 228988,
            from_app_id: 228980,
            from_name: "Steamworks Common Redistributables".into(),
            from_install_dir: "Steamworks Shared".into(),
            from_build_id: 24098430,
            manifest_id: 6645201662696499616,
            size: 29212173,
            install_script: Some("_CommonRedist\\vcredist\\2019\\installscript.vdf".into()),
            library: fx.internal.clone(),
        };
        assert!(register_shared_depot(&shared, OWNER, 5).unwrap());
        let text = fs::read_to_string(fx.internal.acf_path(228980)).unwrap();
        let acf = kv::parse_file(&fx.internal.acf_path(228980)).unwrap();
        assert_eq!(kv::get_u64(&acf, "SizeOnDisk"), 29212173);
        let names: Vec<&str> = acf.children.iter().map(|c| c.name.as_str()).collect();
        let pos = |n: &str| names.iter().position(|x| *x == n).unwrap();
        assert!(pos("InstalledDepots") + 1 == pos("InstallScripts"));
        assert!(pos("InstallScripts") < pos("UserConfig"));
        assert!(text.ends_with("\t\"UserConfig\"\n\t{\n\t}\n\t\"MountedConfig\"\n\t{\n\t}\n}\n"));
        let _ = fs::remove_dir_all(&fx.base);
    }
}
