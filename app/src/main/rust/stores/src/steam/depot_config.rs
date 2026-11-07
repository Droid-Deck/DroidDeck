//! Resume journal for one app's download, kept in a caller-given app-private directory (never in
//! the game folder, which must end up exactly like one the Steam client made).
//!
//! Layout of `<journal_root>/<appid>/`: `depot.config` (JSON: the build the journal belongs to and
//! the finished manifest per depot, or the in-progress sentinel while a depot is being written)
//! and `<depot>_<manifest>.manifest` (the raw CDN manifest, so a resumed run does not refetch it).

use serde_json::{json, Value};
use std::collections::BTreeMap;
use std::fs::{self, File};
use std::io::Write;
use std::path::{Path, PathBuf};

pub const INVALID_MANIFEST_ID: u64 = 0x7fff_ffff_ffff_ffff;

pub fn app_journal_dir(journal_root: impl AsRef<Path>, app_id: u32) -> PathBuf {
    journal_root.as_ref().join(app_id.to_string())
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct DepotConfigStore {
    dir: PathBuf,
    build_id: u64,
    installed: BTreeMap<u32, u64>,
}

impl DepotConfigStore {
    /// Loads the journal; a journal written for another build is dropped (its depot records and
    /// cached manifests no longer describe what this run installs).
    pub fn load(dir: impl Into<PathBuf>, build_id: u64) -> Self {
        let dir = dir.into();
        let mut store = Self {
            dir,
            build_id,
            installed: BTreeMap::new(),
        };
        let Ok(text) = fs::read_to_string(store.config_path()) else {
            return store;
        };
        let Ok(value) = serde_json::from_str::<Value>(&text) else {
            return store;
        };
        let journal_build = value.get("buildId").and_then(Value::as_u64).unwrap_or(0);
        if journal_build != build_id {
            store.discard();
            return store;
        }
        if let Some(obj) = value.get("installedManifestIDs").and_then(Value::as_object) {
            for (key, value) in obj {
                if let (Ok(depot_id), Some(manifest_id)) = (key.parse::<u32>(), value.as_u64()) {
                    store.installed.insert(depot_id, manifest_id);
                }
            }
        }
        store
    }

    pub fn dir(&self) -> &Path {
        &self.dir
    }

    pub fn manifest_cache_path(&self, depot_id: u32, manifest_id: u64) -> PathBuf {
        self.dir.join(format!("{depot_id}_{manifest_id}.manifest"))
    }

    pub fn is_installed(&self, depot_id: u32, manifest_id: u64) -> bool {
        self.installed
            .get(&depot_id)
            .is_some_and(|installed| *installed == manifest_id && *installed != INVALID_MANIFEST_ID)
    }

    pub fn begin_depot(&mut self, depot_id: u32) -> bool {
        self.installed.insert(depot_id, INVALID_MANIFEST_ID);
        self.save()
    }

    pub fn finish_depot(&mut self, depot_id: u32, manifest_id: u64) -> bool {
        self.installed.insert(depot_id, manifest_id);
        self.save()
    }

    pub fn read_cached_manifest(&self, depot_id: u32, manifest_id: u64) -> Option<Vec<u8>> {
        let bytes = fs::read(self.manifest_cache_path(depot_id, manifest_id)).ok()?;
        (!bytes.is_empty()).then_some(bytes)
    }

    pub fn write_cached_manifest(&self, depot_id: u32, manifest_id: u64, raw: &[u8]) -> bool {
        atomic_write_synced(&self.manifest_cache_path(depot_id, manifest_id), raw)
    }

    /// Removes the whole journal directory (after a successful install, or when it is stale).
    pub fn discard(&mut self) {
        self.installed.clear();
        let _ = fs::remove_dir_all(&self.dir);
    }

    fn config_path(&self) -> PathBuf {
        self.dir.join("depot.config")
    }

    fn save(&self) -> bool {
        let ids: serde_json::Map<String, Value> = self
            .installed
            .iter()
            .map(|(depot, manifest)| (depot.to_string(), json!(manifest)))
            .collect();
        let Ok(bytes) = serde_json::to_string_pretty(&json!({
            "buildId": self.build_id,
            "installedManifestIDs": ids,
        })) else {
            return false;
        };
        atomic_write_synced(&self.config_path(), bytes.as_bytes())
    }
}

pub fn atomic_write_synced(final_path: &Path, bytes: &[u8]) -> bool {
    let Some(parent) = final_path.parent() else {
        return false;
    };
    if fs::create_dir_all(parent).is_err() {
        return false;
    }
    let mut tmp_name = final_path
        .file_name()
        .map(|n| n.to_os_string())
        .unwrap_or_default();
    tmp_name.push(".tmp");
    let tmp_path = final_path.with_file_name(tmp_name);
    let mut file = match File::create(&tmp_path) {
        Ok(file) => file,
        Err(_) => return false,
    };
    if file.write_all(bytes).is_err() || file.sync_all().is_err() {
        let _ = fs::remove_file(&tmp_path);
        return false;
    }
    drop(file);
    if fs::rename(&tmp_path, final_path).is_err() {
        let _ = fs::remove_file(&tmp_path);
        return false;
    }
    if let Ok(dir) = File::open(parent) {
        let _ = dir.sync_all();
    }
    true
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn journal_roundtrips_marks_in_progress_and_drops_other_builds() {
        let root = temp_dir("journal");
        let dir = app_journal_dir(&root, 291550);
        let mut store = DepotConfigStore::load(&dir, 26465982);
        assert!(!store.is_installed(291551, 1));
        assert!(store.begin_depot(291551));
        assert!(!DepotConfigStore::load(&dir, 26465982).is_installed(291551, INVALID_MANIFEST_ID));
        assert!(store.finish_depot(291551, 4224536525702420323));
        assert!(store.write_cached_manifest(291551, 4224536525702420323, b"raw"));
        let loaded = DepotConfigStore::load(&dir, 26465982);
        assert!(loaded.is_installed(291551, 4224536525702420323));
        assert_eq!(
            loaded.read_cached_manifest(291551, 4224536525702420323),
            Some(b"raw".to_vec())
        );
        let other_build = DepotConfigStore::load(&dir, 1);
        assert!(!other_build.is_installed(291551, 4224536525702420323));
        assert!(!dir.exists());
        let _ = fs::remove_dir_all(&root);
    }

    fn temp_dir(name: &str) -> PathBuf {
        std::env::temp_dir().join(format!(
            "droiddeck_steam_{name}_{}",
            std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .unwrap()
                .as_nanos()
        ))
    }
}
