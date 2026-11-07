//! One depot from key to files: depot decryption key, manifest request code, the manifest (from
//! the journal when a cancelled run already fetched it), filename decryption, then the writer.

use crate::steam::cdn;
use crate::steam::content_manifest::ContentManifest;
use crate::steam::depot_config::DepotConfigStore;
use crate::steam::depot_writer::{write_depot, DepotWriteOutcome, DepotWriteRequest};
use crate::steam::pb::ccontentserverdirectory::CContentServerDirectoryServerInfo;
use crate::steam::session::{CodeResolution, KeyResolution, Session};
use std::path::Path;
use std::sync::atomic::AtomicBool;

#[derive(Clone, Debug, Eq, PartialEq)]
pub enum DepotError {
    Cancelled,
    AccessDenied(u32),
    Failed(String),
}

impl DepotError {
    pub fn message(&self) -> String {
        match self {
            DepotError::Cancelled => "cancelled".to_string(),
            DepotError::AccessDenied(depot) => format!("access_denied: depot {depot}"),
            DepotError::Failed(msg) => msg.clone(),
        }
    }
}

pub struct PreparedDepot {
    pub depot_id: u32,
    pub manifest_id: u64,
    pub key: Vec<u8>,
    pub manifest: ContentManifest,
}

impl Drop for PreparedDepot {
    fn drop(&mut self) {
        zeroize::Zeroize::zeroize(&mut self.key);
    }
}

/// Resolves the key and the manifest of `depot_id` (owned by `app_id`, the app the key and
/// request code are asked for).
#[allow(clippy::too_many_arguments)]
pub fn prepare_depot(
    session: &Session,
    servers: &[CContentServerDirectoryServerInfo],
    journal: Option<&DepotConfigStore>,
    ca_bundle_path: &str,
    app_id: u32,
    depot_id: u32,
    manifest_id: u64,
    cancel: &AtomicBool,
) -> Result<PreparedDepot, DepotError> {
    let key = match session.depot_key(app_id, depot_id, cancel) {
        KeyResolution::Granted(key) => key,
        KeyResolution::AccessDenied => return Err(DepotError::AccessDenied(depot_id)),
        KeyResolution::Cancelled => return Err(DepotError::Cancelled),
        KeyResolution::Unavailable => {
            return Err(DepotError::Failed(format!(
                "depot key unavailable for depot {depot_id}"
            )))
        }
    };
    let raw = match journal.and_then(|j| j.read_cached_manifest(depot_id, manifest_id)) {
        Some(raw) => raw,
        None => {
            let servers = cdn::usable_servers(servers);
            let code = match session.manifest_code(app_id, depot_id, manifest_id, cancel) {
                CodeResolution::Code(code) => code,
                CodeResolution::AccessDenied => return Err(DepotError::AccessDenied(depot_id)),
                CodeResolution::Cancelled => return Err(DepotError::Cancelled),
                CodeResolution::Unavailable => {
                    return Err(DepotError::Failed(format!(
                        "manifest request code unavailable for depot {depot_id}"
                    )))
                }
            };
            let ca = ca_bundle_path;
            let mut fetched =
                cdn::fetch_manifest_with_retry(&servers, depot_id, manifest_id, code, ca, cancel);
            if !fetched.ok() && fetched.error != "cancelled" {
                // Request codes rotate every few minutes: one more pass with a fresh one.
                if let CodeResolution::Code(fresh) =
                    session.manifest_code(app_id, depot_id, manifest_id, cancel)
                {
                    if fresh != code {
                        fetched = cdn::fetch_manifest_with_retry(
                            &servers,
                            depot_id,
                            manifest_id,
                            fresh,
                            ca,
                            cancel,
                        );
                    }
                }
            }
            if fetched.error == "cancelled" {
                return Err(DepotError::Cancelled);
            }
            if !fetched.ok() {
                return Err(DepotError::Failed(format!(
                    "manifest fetch failed for depot {depot_id}: {}",
                    fetched.error
                )));
            }
            if let Some(journal) = journal {
                let _ = journal.write_cached_manifest(depot_id, manifest_id, &fetched.raw_manifest);
            }
            fetched.raw_manifest
        }
    };
    let mut manifest = ContentManifest::parse(&raw)
        .ok_or_else(|| DepotError::Failed(format!("manifest parse failed for depot {depot_id}")))?;
    if !manifest.decrypt_filenames(&key) {
        return Err(DepotError::Failed(format!(
            "filename decryption failed for depot {depot_id}"
        )));
    }
    Ok(PreparedDepot {
        depot_id,
        manifest_id,
        key,
        manifest,
    })
}

#[allow(clippy::too_many_arguments)]
pub fn download_prepared(
    prepared: &PreparedDepot,
    servers: &[CContentServerDirectoryServerInfo],
    target_dir: &Path,
    ca_bundle_path: &str,
    max_workers: usize,
    process_workers: usize,
    cancel: &AtomicBool,
    progress: &(dyn Fn(u64) + Sync),
    log: &(dyn Fn(&str) + Sync),
) -> DepotWriteOutcome {
    let request = DepotWriteRequest {
        manifest: &prepared.manifest,
        depot_key: &prepared.key,
        servers,
        target_dir,
        ca_bundle_path,
        max_workers,
        process_workers,
    };
    write_depot(&request, cancel, progress, log)
}
