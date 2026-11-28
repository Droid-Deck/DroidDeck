use std::collections::{HashMap, HashSet};
use std::fs;
use std::io::Read;
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicU64, Ordering};
use std::time::Duration;

use serde_json::{json, Value};
use sha1::{Digest, Sha1 as Sha1Hasher};

use super::chunk;
use super::manifest::{self, ChunkInfo, FileEntry, Guid, PartRef, Sha1};
use super::net::{self, Body, Request};
use super::saveformat::{self, safe_save_path};
use super::timefmt;

const STORAGE_HOST: &str = "https://datastorage-public-service-liveegs.live.use1a.on.epicgames.com";

/// Download attempts per cloud read: one try plus two retries.
const READ_ATTEMPTS: usize = 3;
const READ_RETRY_DELAYS: [Duration; READ_ATTEMPTS - 1] = [Duration::from_millis(500), Duration::from_millis(1500)];

fn base(account_id: &str, app_name: &str) -> String {
    format!(
        "{STORAGE_HOST}/api/v1/access/egstore/savesync/{}/{}/",
        net::percent_encode(account_id),
        net::percent_encode(app_name)
    )
}

pub fn name_of(key: &str, app_name: &str) -> String {
    let marker = format!("/{app_name}/");
    if let Some(at) = key.find(&marker) {
        return key[at + marker.len()..].to_string();
    }
    let lead = format!("{app_name}/");
    key.strip_prefix(&lead).unwrap_or(key).to_string()
}

pub fn read_listing(answer: &Value, app_name: &str) -> Vec<Value> {
    let Some(files) = answer.get("files").and_then(|f| f.as_object()) else {
        return Vec::new();
    };
    files
        .iter()
        .map(|(key, f)| {
            let modified = super::timefmt::iso_seconds(net::str_of(f, "lastModified"))
                .map(|s| s * 1000)
                .unwrap_or(-1);
            json!({
                "name": name_of(key, app_name),
                "modified": modified,
                "hash": net::str_of(f, "hash"),
                "readLink": net::str_of(f, "readLink"),
            })
        })
        .filter(|f| !net::str_of(f, "name").is_empty())
        .collect()
}

pub fn list(access_token: &str, account_id: &str, app_name: &str) -> Result<Vec<Value>, String> {
    list_with_time(access_token, account_id, app_name).map(|(files, _)| files)
}

/// The listing plus the server time its HTTP `Date` header carried, unix seconds.
fn list_with_time(
    access_token: &str,
    account_id: &str,
    app_name: &str,
) -> Result<(Vec<Value>, Option<i64>), String> {
    let url = base(account_id, app_name);
    let reply = net::send(Request::get(&url).bearer(access_token))?;
    if reply.status == 404 {
        return Ok((Vec::new(), reply.date));
    }
    if !reply.ok() {
        return Err(format!("Epic cloud list HTTP {}", reply.status));
    }
    let answer = reply.json().ok_or("Epic cloud list could not be read")?;
    Ok((read_listing(&answer, app_name), reply.date))
}

pub fn write_links(access_token: &str, account_id: &str, app_name: &str, names: &[String]) -> Result<Value, String> {
    let url = base(account_id, app_name);
    let body = json!({ "files": names }).to_string();
    let reply = net::send(Request::post(&url, Body::Json(body)).bearer(access_token))?;
    if !reply.ok() {
        return Err(format!("Epic cloud upload request HTTP {}", reply.status));
    }
    let answer = reply.json().ok_or("Epic cloud upload answer could not be read")?;
    let mut links = serde_json::Map::new();
    if let Some(files) = answer.get("files").and_then(|f| f.as_object()) {
        for (key, f) in files {
            let link = net::str_of(f, "writeLink");
            if !link.is_empty() {
                links.insert(name_of(key, app_name), json!(link));
            }
        }
    }
    Ok(Value::Object(links))
}

/// Why a single download attempt failed; the retry policy keys off this.
#[derive(Debug, PartialEq, Eq)]
enum ReadFailure {
    /// The request never produced an HTTP answer.
    Network(String),
    /// The server answered with a non-success status.
    Http(u16),
}

impl ReadFailure {
    fn message(&self) -> String {
        match self {
            ReadFailure::Network(text) => format!("Epic cloud download failed: {text}"),
            ReadFailure::Http(status) => format!("Epic cloud download HTTP {status}"),
        }
    }
}

fn retryable(failure: &ReadFailure) -> bool {
    match failure {
        ReadFailure::Network(_) => true,
        ReadFailure::Http(status) => *status == 429 || (500..600).contains(status),
    }
}

fn read_once(link: &str) -> Result<Vec<u8>, ReadFailure> {
    let reply = net::send(Request::get(link).read_timeout(Duration::from_secs(30))).map_err(ReadFailure::Network)?;
    if !reply.ok() {
        return Err(ReadFailure::Http(reply.status));
    }
    Ok(reply.body)
}

pub fn read(link: &str) -> Result<Vec<u8>, String> {
    for attempt in 0..READ_ATTEMPTS {
        if attempt > 0 {
            std::thread::sleep(READ_RETRY_DELAYS[(attempt - 1).min(READ_RETRY_DELAYS.len() - 1)]);
        }
        match read_once(link) {
            Ok(body) => return Ok(body),
            Err(failure) => {
                if !retryable(&failure) || attempt + 1 == READ_ATTEMPTS {
                    return Err(failure.message());
                }
            }
        }
    }
    Err("Epic cloud download failed".into())
}

pub fn write(link: &str, data: Vec<u8>) -> Result<(), String> {
    let reply = net::send(Request::put(link, Body::Bytes(data)))?;
    if !reply.ok() {
        return Err(format!("Epic cloud upload HTTP {}", reply.status));
    }
    Ok(())
}

/// The cloud surface `publish` needs, so unit tests can drive a publish entirely in
/// memory. The real implementation rides the listing/download/write operations above.
pub trait Cloud {
    /// The save listing, plus the server time its answer carried in the HTTP `Date`
    /// header (unix seconds), if readable.
    fn list(
        &self,
        access_token: &str,
        account_id: &str,
        app_name: &str,
    ) -> Result<(Vec<Value>, Option<i64>), String>;
    fn read(&self, link: &str) -> Result<Vec<u8>, String>;
    fn write_links(
        &self,
        access_token: &str,
        account_id: &str,
        app_name: &str,
        names: &[String],
    ) -> Result<Value, String>;
    fn write(&self, link: &str, data: Vec<u8>) -> Result<(), String>;
}

/// The real cloud: the same operations the rest of this module uses.
pub struct LiveCloud;

impl Cloud for LiveCloud {
    fn list(
        &self,
        access_token: &str,
        account_id: &str,
        app_name: &str,
    ) -> Result<(Vec<Value>, Option<i64>), String> {
        list_with_time(access_token, account_id, app_name)
    }

    fn read(&self, link: &str) -> Result<Vec<u8>, String> {
        read(link)
    }

    fn write_links(
        &self,
        access_token: &str,
        account_id: &str,
        app_name: &str,
        names: &[String],
    ) -> Result<Value, String> {
        write_links(access_token, account_id, app_name, names)
    }

    fn write(&self, link: &str, data: Vec<u8>) -> Result<(), String> {
        write(link, data)
    }
}

/// Why a publish gave up. A conflict is not a plain error: the Kotlin side must be able
/// to tell a cloud that moved on (someone else uploaded) from any other failure.
#[derive(Debug, PartialEq, Eq)]
pub enum PublishError {
    /// The cloud's newest manifest is no longer the one this publish was based on.
    Conflict { latest: String },
    /// Two names that differ only in case while both spellings exist as separate files:
    /// honouring either would flip-flop between them on every sync. Reported with
    /// `collision: true`; nothing was written.
    Collision(String),
    /// A refusal that no retry can fix: an unreadable manifest, an unsafe or non-ASCII
    /// name. Reported with `permanent: true`. A missing save file is a plain retriable
    /// failure instead.
    Permanent(String),
    Failed(String),
}

impl PublishError {
    fn permanent(e: impl Into<String>) -> Self {
        PublishError::Permanent(e.into())
    }
}

impl From<String> for PublishError {
    fn from(e: String) -> Self {
        PublishError::Failed(e)
    }
}

impl From<&str> for PublishError {
    fn from(e: &str) -> Self {
        PublishError::Failed(e.to_string())
    }
}

/// Uploads the save files as a new EGL chunked snapshot, based on `base_manifest`. The
/// file set is the previous manifest's names plus `overlay`, where an overlay name that
/// exactly matches a manifest name replaces that file's content. One that differs only in
/// case is the game renaming a save: refused while the old spelling still sits next to
/// the new one as separate files, since honouring either would flip-flop the two copies
/// on every sync, but an old spelling that is gone locally just drops out and the publish
/// goes through under the new name. Everything else comes from the mirror under
/// `cache_dir/files`, then `save_dir`, and a missing file aborts the publish rather than
/// dropping it silently. All name-level refusals happen before any file is read or any
/// byte is uploaded, so they leave nothing behind. Everything streams: files are read one
/// at a time and every finished block is either reused from the previous manifest (by
/// SHA-1, when its chunk is still in the cloud) or encoded with a fresh GUID and uploaded
/// immediately, so memory holds at most one file and one block. Until the manifest PUT
/// succeeds every failure is answered before the cloud's logical state changes — chunks
/// already written stay behind as unreferenced orphans, and the manifest simply never
/// appears. Once the PUT succeeds the publish is committed, and the only remaining step —
/// rewriting the local mirror — is cache maintenance whose failure is logged but not
/// returned as an error (the next cloud.snapshot rebuilds the mirror from the new
/// manifest). EGL judges newness by manifest name (a UTC timestamp), so the new name is
/// stamped from the listing's HTTP Date (device clock as fallback) and forced strictly
/// after the newest existing one.
pub fn publish(
    cloud: &dyn Cloud,
    access_token: &str,
    account_id: &str,
    app_name: &str,
    cache_dir: &str,
    save_dir: &str,
    overlay: &[String],
    base_manifest: &str,
) -> Result<Value, PublishError> {
    if cache_dir.is_empty() {
        return Err("Epic cloud publish needs a cache directory".into());
    }
    if save_dir.is_empty() || !Path::new(save_dir).is_dir() {
        return Err("the save directory does not exist".into());
    }
    // 1. The cloud must still point at the manifest this publish is based on.
    let (files, server_time) = cloud.list(access_token, account_id, app_name)?;
    let cloud_names: Vec<&str> = files.iter().map(|f| net::str_of(f, "name")).collect();
    let Some(latest) = latest_manifest(cloud_names.iter().copied()) else {
        return Err(PublishError::permanent("the cloud keeps no save manifest"));
    };
    if latest != base_manifest {
        return Err(PublishError::Conflict { latest });
    }
    let read_links: HashMap<&str, &str> = files
        .iter()
        .map(|f| (net::str_of(f, "name"), net::str_of(f, "readLink")))
        .filter(|(_, link)| !link.is_empty())
        .collect();
    // 2. The manifest this publish builds on; a game whose manifest cannot be read keeps
    //    its download-only behaviour — permanently, the bytes will never parse.
    let link = read_links
        .get(base_manifest)
        .copied()
        .ok_or_else(|| format!("the cloud listing has no download link for {base_manifest}"))?;
    let previous_bytes = cloud.read(link)?;
    let previous = saveformat::read(&previous_bytes).map_err(|e| {
        PublishError::permanent(format!("save manifest {base_manifest} cannot be read: {e}"))
    })?;

    // 3. Collect the file set in packing order (EGL's case-insensitive sort) with each
    //    file's source resolved: overlay from `save_dir`, the rest from the mirror first,
    //    then `save_dir`. Every name of the merged set is screened — including the ones the
    //    previous manifest brought along — before anything is read, uploaded or staged.
    let save_root = Path::new(save_dir);
    let mirror = Path::new(cache_dir).join("files");
    let mut set: Vec<(String, bool)> = previous
        .files
        .iter()
        .map(|f| (f.path.clone(), false))
        .collect();
    let mut seen_overlay: HashSet<String> = HashSet::new();
    // Old spellings dropped by a case-only rename; the mirror must not keep them.
    let mut dropped: Vec<String> = Vec::new();
    for name in overlay {
        let key = name.to_lowercase();
        // Two overlay entries that collide case-insensitively would fight over one slot
        // of the packed stream in an order-dependent way; the caller must pick one name.
        if !seen_overlay.insert(key.clone()) {
            return Err(PublishError::Collision(format!("overlay lists {name} twice")));
        }
        // An exact manifest name replaces that file's content. A case-only match is a
        // game renaming its save folder: when the old spelling still sits next to the new
        // one as a separate file the ownership is ambiguous and the publish is refused,
        // but an old spelling that is gone (or a case-folding volume where both spellings
        // resolve to the same file) is just the rename landing here — drop it and publish
        // under the new one.
        match set.iter_mut().find(|(n, _)| n == name) {
            Some(slot) => *slot = (name.clone(), true),
            None => {
                if let Some(old) = previous
                    .files
                    .iter()
                    .find(|f| f.path.to_lowercase() == key)
                    .map(|f| f.path.clone())
                {
                    if let Some(old_path) = resolve_under(save_root, &old)? {
                        let same_file = resolve_under(save_root, name)?
                            .map_or(false, |new_path| new_path == old_path);
                        if !same_file {
                            return Err(PublishError::Collision(format!(
                                "overlay save file {name} differs only in case from the manifest's {old}: both spellings exist in the save folder"
                            )));
                        }
                    }
                    dropped.push(old.clone());
                    set.retain(|(n, _)| n != &old);
                }
                set.push((name.clone(), true));
            }
        }
    }
    set.sort_by(|a, b| a.0.to_lowercase().cmp(&b.0.to_lowercase()));
    for (name, _) in &set {
        saveformat::validate_save_name(name).map_err(PublishError::Permanent)?;
    }
    let mut slots: Vec<(String, PathBuf)> = Vec::with_capacity(set.len());
    for (name, from_overlay) in &set {
        // A missing file is transient (a save deleted mid-sync, an incomplete mirror the
        // next snapshot fills in), so it stays a plain retriable failure; only problems
        // with the names or the manifest itself are permanent.
        let src = if *from_overlay {
            resolve_under(save_root, name)?.ok_or_else(|| {
                format!("overlay save file {name} is missing from {save_dir}")
            })?
        } else {
            match resolve_under(&mirror, name)? {
                Some(path) => path,
                None => resolve_under(save_root, name)?
                    .ok_or_else(|| {
                        format!("save file {name} is missing from both the mirror and {save_dir}")
                    })?,
            }
        };
        slots.push((name.clone(), src));
    }
    let cache = Path::new(cache_dir);
    // A cache dir that cannot take a single directory cannot take the mirror refresh
    // either: fail here, while the cloud is still untouched.
    probe_writable(cache)?;

    // 4. Pack and upload in one streaming pass: files are read one at a time, every full
    //    window is either reused from the previous manifest (by SHA-1) or encoded with a
    //    fresh GUID and uploaded immediately, and its bytes are dropped right after.
    let mut chunks: Vec<ChunkInfo> = Vec::new();
    let mut uploaded: Vec<String> = Vec::new();
    let mut reused: Vec<String> = Vec::new();
    let mut on_block = |block: &[u8]| {
        upload_block(
            cloud,
            access_token,
            account_id,
            app_name,
            &previous,
            &cloud_names,
            block,
            &mut chunks,
            &mut uploaded,
            &mut reused,
        )
    };
    let mut packer = saveformat::BlockPacker::new();
    let mut published: Vec<(String, u64, String)> = Vec::new(); // name, size, sha1 hex
    for (name, src) in &slots {
        let content = fs::read(src).map_err(|e| format!("save read {}: {e}", src.display()))?;
        let sha = packer.push(name, &content, &mut |_, block| on_block(block))?;
        published.push((name.clone(), content.len() as u64, manifest::sha1_hex(&sha)));
    }
    let files = packer.finish(&mut |_, block| on_block(block))?;

    // 5. The stamp: strictly after the newest manifest.
    let stamp = next_stamp(server_time, &latest);

    // 6. The manifest: meta and custom fields carry over, only BuildVersion becomes the
    //    new stamp; chunks follow the packed stream order; the body stays uncompressed.
    let mut meta = previous.meta.clone();
    meta.build_version = stamp.clone();
    let file_count = files.len();
    let manifest = saveformat::SaveManifest {
        version: previous.version,
        meta,
        chunks,
        files,
        links: vec![String::new(); file_count],
        file_flags: vec![0; file_count],
        custom: previous.custom.clone(),
    };
    let manifest_name = saveformat::manifest_cloud_path(&stamp);
    let write_map = cloud.write_links(access_token, account_id, app_name, &[manifest_name.clone()])?;
    let write_link = net::str_of(&write_map, &manifest_name);
    if write_link.is_empty() {
        return Err(format!("the cloud listing has no write link for {manifest_name}").into());
    }
    // From here the publish is committed: the cloud's newest manifest is ours. Only the
    // local mirror refresh remains, and it is cache maintenance — never reported as a
    // publish error, since the next cloud.snapshot rebuilds it from the new manifest.
    cloud.write(&write_link, saveformat::write(&manifest))?;

    if let Err(e) = publish_mirror(cache, &slots, &dropped) {
        eprintln!("cloud.publish: mirror update failed, the next snapshot will rebuild it: {e}");
    }

    Ok(json!({
        "mode": "manifest",
        "manifest": manifest_name,
        "files": published
            .iter()
            .map(|(name, size, sha1)| json!({ "name": name, "size": size, "sha1": sha1 }))
            .collect::<Vec<_>>(),
        "uploaded": uploaded,
        "reused": reused,
        "stamp": stamp,
    }))
}

/// Decides one packed block's fate and gets it to the cloud. A block already decided in
/// this publish reuses that decision (two identical blocks share one chunk); otherwise a
/// chunk with the same SHA-1 in the previous manifest is reused untouched, as long as the
/// chunk is still in the cloud, since EGL prunes chunks of old manifests. A truly fresh
/// block gets a random GUID, its own write link and an immediate upload, so its bytes
/// never linger in memory. A failure mid-stream leaves already-uploaded chunks behind as
/// unreferenced orphans — the manifest (the cloud's logical state) simply never appears.
fn upload_block(
    cloud: &dyn Cloud,
    access_token: &str,
    account_id: &str,
    app_name: &str,
    previous: &saveformat::SaveManifest,
    cloud_names: &[&str],
    block: &[u8],
    chunks: &mut Vec<ChunkInfo>,
    uploaded: &mut Vec<String>,
    reused: &mut Vec<String>,
) -> Result<Guid, String> {
    let sha: Sha1 = Sha1Hasher::digest(block).into();
    // A block decided earlier in this publish wins first: the second occurrence just
    // reuses that chunk's GUID. The chunk list keys chunks by GUID, so it carries a
    // single entry that several parts can point at; how EGL would list identical blocks
    // has no multi-chunk real sample to check against.
    if let Some(decided) = chunks.iter().find(|c| c.sha1 == Some(sha)).cloned() {
        return Ok(decided.guid);
    }
    if let Some(old) = previous
        .chunks
        .iter()
        .find(|c| c.sha1 == Some(sha) && cloud_names.contains(&saveformat::chunk_cloud_path(c).as_str()))
    {
        reused.push(saveformat::chunk_cloud_path(old));
        chunks.push(old.clone());
        return Ok(old.guid);
    }
    let guid = random_guid()?;
    let built = chunk::encode(block, &guid)?;
    let info = built.info(&guid);
    let cloud_path = saveformat::chunk_cloud_path(&info);
    let write_map = cloud.write_links(access_token, account_id, app_name, &[cloud_path.clone()])?;
    let link = net::str_of(&write_map, &cloud_path);
    if link.is_empty() {
        return Err(format!("the cloud listing has no write link for {cloud_path}"));
    }
    cloud.write(link, built.bytes)?;
    uploaded.push(cloud_path);
    chunks.push(info);
    Ok(guid)
}

/// Resolves `name` inside `dir` to a readable file's path: `None` when the name or the
/// folder does not resolve, an error when the name is unsafe or resolves outside `dir`
/// once symlinks are followed. Names are screened by `safe_save_path` first.
fn resolve_under(dir: &Path, name: &str) -> Result<Option<PathBuf>, String> {
    if !safe_save_path(name) {
        return Err(format!("unsafe save path: {name}"));
    }
    let (Ok(resolved), Ok(root)) = (dir.join(name).canonicalize(), dir.canonicalize()) else {
        return Ok(None); // the name or the folder itself does not exist
    };
    if !resolved.starts_with(&root) {
        return Err(format!("save path {name} escapes {}", dir.display()));
    }
    if !resolved.is_file() {
        return Ok(None);
    }
    Ok(Some(resolved))
}

/// Cheap check that the cache dir can take the mirror refresh at all, so a wedged or full
/// cache fails the publish before anything is written to the cloud. Leaves nothing behind.
fn probe_writable(cache: &Path) -> Result<(), String> {
    let probe = cache.join(format!(
        "publish-probe-{}-{}",
        std::process::id(),
        STAGING_SEQ.fetch_add(1, Ordering::Relaxed)
    ));
    let result = fs::create_dir(&probe);
    let _ = fs::remove_dir(&probe);
    result.map_err(|e| format!("cache dir is not writable: {e}"))
}

/// Publish-side mirror refresh that never holds more than one file in memory: each save
/// is streamed from its source into a staging folder and renamed into the mirror, and
/// anything the file set no longer lists is pruned — the same end state `stage_files` +
/// `publish_files` give `snapshot`. `dropped` names old spellings retired by a case-only
/// rename and are removed up front, before the new spelling lands (on a case-folding
/// volume they are the same path). The staging folder is removed on any failure.
fn publish_mirror(cache: &Path, files: &[(String, PathBuf)], dropped: &[String]) -> Result<(), String> {
    let staging = cache.join(format!(
        "files.new-{}-{}",
        std::process::id(),
        STAGING_SEQ.fetch_add(1, Ordering::Relaxed)
    ));
    let _ = fs::remove_dir_all(&staging);
    for name in dropped {
        let _ = fs::remove_file(cache.join("files").join(name));
    }
    let result = (|| -> Result<(), String> {
        for (name, src) in files {
            let tmp = staging.join(name);
            let parent = tmp.parent().ok_or_else(|| format!("save path {name} has no parent"))?;
            fs::create_dir_all(parent).map_err(|e| format!("save folder: {e}"))?;
            fs::copy(src, &tmp).map_err(|e| format!("save copy {name}: {e}"))?;
            let target = cache.join("files").join(name);
            let parent = target.parent().ok_or_else(|| format!("save path {name} has no parent"))?;
            fs::create_dir_all(parent).map_err(|e| format!("save folder: {e}"))?;
            fs::rename(&tmp, &target).map_err(|e| format!("save publish {name}: {e}"))?;
        }
        let keep: HashSet<String> = files.iter().map(|(name, _)| name.clone()).collect();
        let files_dir = cache.join("files");
        prune_extras(&files_dir, &files_dir, &keep);
        Ok(())
    })();
    // Success or failure, every file has either been renamed away or never landed: the
    // staging folder must never survive the refresh.
    let _ = fs::remove_dir_all(&staging);
    result
}

/// The stamp for a new manifest: the server clock (device clock as fallback), forced
/// strictly after the newest existing manifest. EGL judges newness by manifest name, and
/// the timestamp format makes name order equal time order — so a stamp past the newest
/// name is also one no existing manifest carries.
fn next_stamp(server_secs: Option<i64>, latest: &str) -> String {
    let mut secs = server_secs.unwrap_or_else(device_seconds);
    let body = latest.strip_prefix("manifests/").unwrap_or(latest);
    let body = body.strip_suffix(".manifest").unwrap_or(body);
    let latest_secs = timefmt::stamp_seconds(body).unwrap_or(0);
    if secs <= latest_secs {
        secs = latest_secs + 1;
    }
    timefmt::save_stamp(secs)
}

/// Fresh 16-byte GUID for a new chunk, read from /dev/urandom like the Amazon module does.
fn random_guid() -> Result<Guid, String> {
    let mut raw = [0u8; 16];
    std::fs::File::open("/dev/urandom")
        .and_then(|mut file| file.read_exact(&mut raw))
        .map_err(|e| format!("no randomness: {e}"))?;
    let mut guid = [0u32; 4];
    for (i, slot) in guid.iter_mut().enumerate() {
        *slot = u32::from_le_bytes(raw[i * 4..i * 4 + 4].try_into().expect("16 bytes"));
    }
    Ok(guid)
}

/// The device clock, only used when the cloud answer carried no usable HTTP Date.
fn device_seconds() -> i64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_secs() as i64)
        .unwrap_or(0)
}

/// The newest save manifest in a cloud listing. Manifest names carry a UTC timestamp,
/// so the lexicographically largest name is the most recent snapshot.
pub fn latest_manifest<'a>(names: impl IntoIterator<Item = &'a str>) -> Option<String> {
    names
        .into_iter()
        .filter(|name| name.starts_with("manifests/") && name.ends_with(".manifest"))
        .max()
        .map(str::to_string)
}

/// Whether a cloud entry belongs to the chunked-save machinery instead of the game's own saves.
pub fn is_mechanical(name: &str) -> bool {
    if name.starts_with("manifests/") || name.ends_with(".chunk") {
        return true;
    }
    ["Chunks/", "ChunksV2/", "ChunksV3/", "ChunksV4/", "ChunksV5/"]
        .iter()
        .any(|prefix| name.starts_with(prefix))
}

/// "manifest" when the cloud keeps an EGL chunked snapshot, "raw" for plain files only.
pub fn cloud_mode(files: &[Value]) -> &'static str {
    if latest_manifest(files.iter().map(|f| net::str_of(f, "name"))).is_some() {
        "manifest"
    } else {
        "raw"
    }
}

#[derive(Debug)]
pub struct AssembledFile {
    pub name: String,
    pub content: Vec<u8>,
    /// Lowercase hex of the manifest hash, empty when the manifest carried none.
    pub sha1: String,
}

/// Builds save files from per-chunk contents supplied by `chunk_of`, joining each file's
/// parts in manifest order and checking its SHA-1 when the manifest carries one.
pub fn assemble(
    files: &[FileEntry],
    mut chunk_of: impl FnMut(&PartRef) -> Result<Vec<u8>, String>,
) -> Result<Vec<AssembledFile>, String> {
    let mut out = Vec::with_capacity(files.len());
    for file in files {
        if !safe_save_path(&file.path) {
            return Err(format!("manifest lists an unsafe save path: {}", file.path));
        }
        let mut content = Vec::new();
        for part in &file.parts {
            let data = chunk_of(part)?;
            let start = part.offset as usize;
            let end = start + part.size as usize;
            if end > data.len() {
                return Err(format!(
                    "save file {} needs bytes {}..{} but chunk {} holds {}",
                    file.path,
                    start,
                    end,
                    manifest::guid_text(&part.guid),
                    data.len()
                ));
            }
            content.extend_from_slice(&data[start..end]);
        }
        let sha1 = match file.sha1 {
            Some(expected) => {
                let got: [u8; 20] = Sha1Hasher::digest(&content).into();
                if got != expected {
                    return Err(format!("save file {} does not match its SHA-1", file.path));
                }
                manifest::sha1_hex(&expected)
            }
            None => String::new(),
        };
        out.push(AssembledFile { name: file.path.clone(), content, sha1 });
    }
    Ok(out)
}

fn chunk_file_name(cloud_path: &str) -> &str {
    cloud_path.rsplit_once('/').map_or(cloud_path, |(_, name)| name)
}

/// Staging names carry the process id and a counter so two concurrent snapshot writers (two
/// threads here, or the app's two processes) never publish each other's half-written `.part`.
static STAGING_SEQ: AtomicU64 = AtomicU64::new(0);

fn store_chunk(dir: &Path, name: &str, data: &[u8]) -> Result<(), String> {
    fs::create_dir_all(dir).map_err(|e| format!("chunk cache: {e}"))?;
    let part = dir.join(format!(
        "{name}.{}-{}.part",
        std::process::id(),
        STAGING_SEQ.fetch_add(1, Ordering::Relaxed)
    ));
    let result = (|| -> std::io::Result<()> {
        fs::write(&part, data)?;
        fs::rename(&part, dir.join(name))
    })();
    if let Err(e) = result {
        let _ = fs::remove_file(&part);
        return Err(format!("chunk cache write: {e}"));
    }
    Ok(())
}

/// Returns the decoded chunk, reusing `dir/<file name>` when its size matches the manifest's
/// CDN size (an unknown size of 0 skips the check), downloading through `link` otherwise.
fn load_chunk(dir: &Path, cloud_path: &str, info: &ChunkInfo, link: Option<&str>) -> Result<Vec<u8>, String> {
    let name = chunk_file_name(cloud_path);
    let path = dir.join(name);
    let cached = fs::metadata(&path)
        .map(|meta| info.cdn_size == 0 || meta.len() == info.cdn_size)
        .unwrap_or(false);
    if cached {
        match fs::read(&path).map(|raw| chunk::decode(&raw, info)) {
            Ok(Ok(content)) => return Ok(content),
            // A corrupt cache entry (truncated by a crash, same-size garbage) must not wedge
            // this game's mirror forever: drop it and download the chunk afresh below. An entry
            // a concurrent refresh renamed away falls through the same way.
            Ok(Err(_)) | Err(_) => {
                let _ = fs::remove_file(&path);
            }
        }
    }
    let link = link
        .filter(|l| !l.is_empty())
        .ok_or_else(|| format!("the cloud listing has no download link for {cloud_path}"))?;
    let raw = read(link)?;
    let content = chunk::decode(&raw, info).map_err(|e| format!("cloud chunk {cloud_path}: {e}"))?;
    store_chunk(dir, name, &raw)?;
    Ok(content)
}

/// Writes the assembled saves into a fresh staging folder under `cache`. On any failure the
/// staging folder is removed and the current mirror is left untouched.
fn stage_files(cache: &Path, files: &[AssembledFile]) -> Result<PathBuf, String> {
    for file in files {
        if !safe_save_path(&file.name) {
            return Err(format!("manifest lists an unsafe save path: {}", file.name));
        }
    }
    let staging = cache.join(format!(
        "files.new-{}-{}",
        std::process::id(),
        STAGING_SEQ.fetch_add(1, Ordering::Relaxed)
    ));
    let _ = fs::remove_dir_all(&staging);
    let write = || -> Result<(), String> {
        for file in files {
            let tmp = staging.join(&file.name);
            let parent = tmp.parent().ok_or_else(|| format!("save path {} has no parent", file.name))?;
            fs::create_dir_all(parent).map_err(|e| format!("save folder: {e}"))?;
            fs::write(&tmp, &file.content).map_err(|e| format!("save write {}: {e}", file.name))?;
        }
        Ok(())
    };
    if let Err(e) = write() {
        let _ = fs::remove_dir_all(&staging);
        return Err(e);
    }
    Ok(staging)
}

/// Deletes every file under `dir` whose path relative to `root` is not in `keep`, then prunes
/// the directories left empty, so the mirror always matches the newest manifest exactly.
fn prune_extras(dir: &Path, root: &Path, keep: &HashSet<String>) {
    // Exact comparison: the mirror must match the newest manifest exactly, including
    // spelling - on a case-sensitive save folder (Android) a case-only renamed file left
    // behind would be adopted as a cloud file and downloaded back over the rename. A
    // case-only rename removes its old spelling up front (see `publish_mirror`).
    let Ok(entries) = fs::read_dir(dir) else { return };
    for entry in entries.flatten() {
        let path = entry.path();
        if path.is_dir() {
            prune_extras(&path, root, keep);
            let _ = fs::remove_dir(&path);
        } else {
            let rel = path
                .strip_prefix(root)
                .map(|p| p.to_string_lossy().replace('\\', "/"))
                .unwrap_or_default();
            if !keep.contains(&rel) {
                let _ = fs::remove_file(&path);
            }
        }
    }
}

/// Moves the staged files into the mirror one atomic rename per file, keeping files whose
/// content already matches, then drops mirror entries the newest manifest no longer lists.
fn publish_files(cache: &Path, staging: &Path, files: &[AssembledFile]) -> Result<(), String> {
    let files_dir = cache.join("files");
    fs::create_dir_all(&files_dir).map_err(|e| format!("save folder: {e}"))?;
    for file in files {
        let target = files_dir.join(&file.name);
        if let Some(parent) = target.parent() {
            fs::create_dir_all(parent).map_err(|e| format!("save folder: {e}"))?;
        }
        let tmp = staging.join(&file.name);
        if fs::read(&target).map(|old| old == file.content).unwrap_or(false) {
            let _ = fs::remove_file(&tmp);
            continue;
        }
        fs::rename(&tmp, &target).map_err(|e| format!("save publish {}: {e}", file.name))?;
    }
    let _ = fs::remove_dir_all(staging);
    let keep: HashSet<String> = files.iter().map(|f| f.name.clone()).collect();
    prune_extras(&files_dir, &files_dir, &keep);
    Ok(())
}

/// Rebuilds the EGL chunked cloud snapshot into `<cacheDir>/files`: downloads the newest
/// `manifests/*.manifest`, then every chunk it references (cached under `<cacheDir>/chunks`),
/// and joins the parts back into whole save files. Returns `"mode":"raw"` when the cloud
/// keeps no manifest, i.e. only plain files.
pub fn snapshot(access_token: &str, account_id: &str, app_name: &str, cache_dir: &str) -> Result<Value, String> {
    if cache_dir.is_empty() {
        return Err("Epic cloud snapshot needs a cache directory".into());
    }
    let files = list(access_token, account_id, app_name)?;
    let names = files.iter().map(|f| net::str_of(f, "name"));
    let Some(manifest_name) = latest_manifest(names) else {
        return Ok(json!({
            "mode": "raw",
            "manifest": "",
            "files": [],
            "reason": "the cloud keeps no save manifest",
        }));
    };
    let links: HashMap<&str, &str> = files
        .iter()
        .map(|f| (net::str_of(f, "name"), net::str_of(f, "readLink")))
        .filter(|(_, link)| !link.is_empty())
        .collect();
    let link = links
        .get(manifest_name.as_str())
        .copied()
        .ok_or_else(|| format!("the cloud listing has no download link for {manifest_name}"))?;
    let bytes = read(link)?;
    let m = manifest::parse(&bytes).map_err(|e| format!("save manifest {manifest_name} cannot be read: {e}"))?;

    let cache = Path::new(cache_dir);
    let chunk_dir = cache.join("chunks");
    let mut held: HashMap<Guid, Vec<u8>> = HashMap::with_capacity(m.chunks.len());
    let mut chunk_of = |part: &PartRef| -> Result<Vec<u8>, String> {
        if let Some(data) = held.get(&part.guid) {
            return Ok(data.clone());
        }
        let info = m
            .chunk(&part.guid)
            .ok_or_else(|| format!("save manifest references unknown chunk {}", manifest::guid_text(&part.guid)))?;
        let cloud_path = m.chunk_path(info);
        let content = load_chunk(&chunk_dir, &cloud_path, info, links.get(cloud_path.as_str()).copied())?;
        held.insert(part.guid, content.clone());
        Ok(content)
    };
    let assembled = assemble(&m.files, &mut chunk_of)?;

    // The mirror is read by other threads (and the app's other process) while this runs, so it is
    // never edited in place: stage everything, then publish one atomic rename at a time, and make
    // the mirror exactly match the newest manifest by dropping entries it no longer lists.
    let staging = stage_files(cache, &assembled)?;
    if let Err(e) = publish_files(cache, &staging, &assembled) {
        let _ = fs::remove_dir_all(&staging);
        return Err(e);
    }
    let listed = assembled
        .iter()
        .map(|file| json!({ "name": file.name, "size": file.content.len(), "sha1": file.sha1 }))
        .collect::<Vec<_>>();
    Ok(json!({ "mode": "manifest", "manifest": manifest_name, "files": listed }))
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::epic::manifest::testing::{build, chunk as test_chunk, fstring, TestFile, TestManifest};

    fn entry(path: &str, sha1: Option<Sha1>, parts: Vec<PartRef>) -> FileEntry {
        FileEntry {
            path: path.into(),
            sha1,
            tags: vec![],
            size: parts.iter().map(|p| p.size as u64).sum(),
            parts,
        }
    }

    /// A version-17 save manifest whose body carries a fourth section with EGL's
    /// custom fields; the parser must keep reading the first three and tolerate the rest.
    fn save_manifest_bytes() -> Vec<u8> {
        let c1 = test_chunk(1, 64);
        let m = TestManifest {
            version: 17,
            build: "SaveGame".into(),
            exe: "".into(),
            prereq: None,
            chunks: vec![c1.clone()],
            files: vec![TestFile {
                path: "Saves/slot1.sav".into(),
                sha1: [7; 20],
                tags: vec![],
                parts: vec![PartRef { guid: c1.guid, offset: 0, size: 8 }],
            }],
            compress: false,
            meta_tail: vec![],
        };
        let mut bytes = build(&m);
        // Uncompressed, build() ends with an empty fourth section: a size header of 9 plus five zero bytes.
        let empty = bytes.split_off(bytes.len() - 9);
        assert_eq!(empty[0..4], 9i32.to_le_bytes());
        let mut fields = Vec::new();
        fields.extend_from_slice(&2i32.to_le_bytes());
        fstring(&mut fields, "CloudBackupFolder");
        fstring(&mut fields, "Saves");
        fstring(&mut fields, "BackupLimit");
        fstring(&mut fields, "3");
        fields.extend_from_slice(&[1, 2, 3]);
        bytes.extend_from_slice(&((fields.len() + 4) as i32).to_le_bytes());
        bytes.extend_from_slice(&fields);
        bytes
    }

    #[test]
    fn names_from_keys() {
        assert_eq!(name_of("acc/Fig/Saves/slot1.sav", "Fig"), "Saves/slot1.sav");
        assert_eq!(name_of("Fig/slot1.sav", "Fig"), "slot1.sav");
        assert_eq!(name_of("slot1.sav", "Fig"), "slot1.sav");
    }

    #[test]
    fn listing() {
        let answer = json!({"files": {
            "acc/Fig/a.sav": {"lastModified": "2024-05-01T10:00:00.000Z", "hash": "abc", "readLink": "https://r/a"},
            "acc/Fig/": {"lastModified": "", "hash": "", "readLink": ""}
        }});
        let files = read_listing(&answer, "Fig");
        assert_eq!(files.len(), 1);
        assert_eq!(files[0]["name"], "a.sav");
        assert_eq!(files[0]["modified"], 1_714_557_600_000i64);
        assert!(read_listing(&json!({}), "Fig").is_empty());
    }

    #[test]
    fn a_save_manifest_with_custom_fields_section_parses() {
        let m = manifest::parse(&save_manifest_bytes()).unwrap();
        assert_eq!(m.version, 17);
        assert_eq!(m.chunk_folder(), "ChunksV4");
        assert_eq!(m.chunks.len(), 1);
        assert_eq!(m.files.len(), 1);
        assert_eq!(m.files[0].path, "Saves/slot1.sav");
        assert_eq!(m.files[0].size, 8);
        assert_eq!(m.files[0].sha1, Some([7; 20]));
    }

    #[test]
    fn latest_manifest_prefers_the_lexically_newest() {
        let names = [
            "ChunksV4/01/AB_0000.chunk",
            "Saves/slot1.sav",
            "manifests/2024.01.02-03.04.05.manifest",
            "manifests/2025.11.20-09.08.07.manifest",
        ];
        assert_eq!(latest_manifest(names).as_deref(), Some("manifests/2025.11.20-09.08.07.manifest"));
        assert_eq!(latest_manifest(["Saves/slot1.sav", "manifests/readme.txt"]), None);
        assert_eq!(latest_manifest([] as [&str; 0]), None);
    }

    #[test]
    fn retry_policy_matches_network_and_transient_http() {
        assert!(retryable(&ReadFailure::Network("timed out".into())));
        for status in [429u16, 500, 502, 503, 599] {
            assert!(retryable(&ReadFailure::Http(status)), "{status}");
        }
        for status in [400u16, 401, 403, 404, 409, 499] {
            assert!(!retryable(&ReadFailure::Http(status)), "{status}");
        }
    }

    #[test]
    fn assembly_joins_parts_in_order_and_checks_sha1() {
        let g1: Guid = [1, 2, 3, 4];
        let g2: Guid = [5, 6, 7, 8];
        let contents: HashMap<Guid, Vec<u8>> =
            HashMap::from([(g1, b"AAAAAA".to_vec()), (g2, b"BB".to_vec())]);
        let fetch = |part: &PartRef| -> Result<Vec<u8>, String> {
            contents.get(&part.guid).cloned().ok_or_else(|| "missing chunk".into())
        };
        let parts = vec![
            PartRef { guid: g1, offset: 0, size: 2 },
            PartRef { guid: g2, offset: 0, size: 2 },
            PartRef { guid: g1, offset: 2, size: 2 },
        ];
        let data = b"AABBAA".to_vec();
        let sha: Sha1 = Sha1Hasher::digest(&data).into();
        let files = vec![entry("Saves/slot1.sav", Some(sha), parts)];
        let out = assemble(&files, fetch).unwrap();
        assert_eq!(out.len(), 1);
        assert_eq!(out[0].name, "Saves/slot1.sav");
        assert_eq!(out[0].content, data);
        assert_eq!(out[0].sha1, manifest::sha1_hex(&sha));
    }

    #[test]
    fn assembly_fails_when_a_file_hash_does_not_match() {
        let g: Guid = [1, 0, 0, 0];
        let contents: HashMap<Guid, Vec<u8>> = HashMap::from([(g, b"AAAAAA".to_vec())]);
        let fetch = |part: &PartRef| -> Result<Vec<u8>, String> {
            contents.get(&part.guid).cloned().ok_or_else(|| "missing chunk".into())
        };
        let files = vec![entry("Saves/a.sav", Some([9; 20]), vec![PartRef { guid: g, offset: 0, size: 6 }])];
        let err = assemble(&files, fetch).unwrap_err();
        assert!(err.contains("Saves/a.sav"), "{err}");
    }

    #[test]
    fn assembly_checks_ranges_and_rejects_unsafe_names() {
        let g: Guid = [9, 9, 9, 9];
        let contents: HashMap<Guid, Vec<u8>> = HashMap::from([(g, b"xyz".to_vec())]);
        let fetch = |part: &PartRef| -> Result<Vec<u8>, String> {
            contents.get(&part.guid).cloned().ok_or_else(|| "missing chunk".into())
        };
        let no_hash = vec![entry("a.sav", None, vec![PartRef { guid: g, offset: 0, size: 3 }])];
        let out = assemble(&no_hash, fetch).unwrap();
        assert_eq!(out[0].content, b"xyz".to_vec());
        assert_eq!(out[0].sha1, "");
        let past_end = vec![entry("a.sav", None, vec![PartRef { guid: g, offset: 2, size: 5 }])];
        assert!(assemble(&past_end, fetch).is_err());
        for bad in ["", "/abs.sav", "../up.sav", "a/../b.sav", "back\\slash.sav", "a//b.sav", "./x.sav"] {
            let files = vec![entry(bad, None, vec![])];
            assert!(assemble(&files, fetch).is_err(), "path {bad:?} must be refused");
        }
    }

    fn assembled(name: &str, content: &[u8]) -> AssembledFile {
        AssembledFile { name: name.into(), content: content.to_vec(), sha1: String::new() }
    }

    fn mirrored(cache: &Path) -> Vec<String> {
        let mut out = Vec::new();
        fn walk(dir: &Path, root: &Path, out: &mut Vec<String>) {
            for entry in fs::read_dir(dir).unwrap().flatten() {
                let path = entry.path();
                if path.is_dir() {
                    walk(&path, root, out);
                } else {
                    out.push(path.strip_prefix(root).unwrap().to_string_lossy().replace('\\', "/"));
                }
            }
        }
        walk(&cache.join("files"), &cache.join("files"), &mut out);
        out.sort();
        out
    }

    #[test]
    fn publishing_replaces_the_mirror_with_the_newest_manifest() {
        let root = std::env::temp_dir().join(format!("dd-savesync-{}", std::process::id()));
        let _ = fs::remove_dir_all(&root);
        let cache = root.join("cache");
        fs::create_dir_all(cache.join("files/Saves")).unwrap();
        fs::write(cache.join("files/Saves/old.sav"), b"stale").unwrap();
        fs::write(cache.join("files/Saves/keep.sav"), b"same").unwrap();
        let files = vec![
            assembled("Saves/keep.sav", b"same"),
            assembled("Saves/new.sav", b"fresh"),
            assembled("More/deep/new2.sav", b"fresh2"),
        ];
        let staging = stage_files(&cache, &files).unwrap();
        publish_files(&cache, &staging, &files).unwrap();
        assert_eq!(mirrored(&cache), vec!["More/deep/new2.sav", "Saves/keep.sav", "Saves/new.sav"]);
        assert_eq!(fs::read(cache.join("files/Saves/new.sav")).unwrap(), b"fresh");
        assert!(!staging.exists());
        let _ = fs::remove_dir_all(&root);
    }

    #[test]
    fn a_failed_staging_leaves_the_mirror_and_no_staging_folder_behind() {
        let root = std::env::temp_dir().join(format!("dd-savesync-bad-{}", std::process::id()));
        let _ = fs::remove_dir_all(&root);
        let cache = root.join("cache");
        fs::create_dir_all(cache.join("files")).unwrap();
        fs::write(cache.join("files/keep.sav"), b"kept").unwrap();
        let files = vec![assembled("good.sav", b"ok"), assembled("../escape.sav", b"bad")];
        assert!(stage_files(&cache, &files).is_err());
        assert_eq!(mirrored(&cache), vec!["keep.sav"]);
        assert_eq!(fs::read(cache.join("files/keep.sav")).unwrap(), b"kept");
        let no_staging = fs::read_dir(&cache)
            .unwrap()
            .flatten()
            .all(|e| !e.file_name().to_string_lossy().starts_with("files.new-"));
        assert!(no_staging);
        let _ = fs::remove_dir_all(&root);
    }

    #[test]
    fn mechanical_cloud_files_are_recognised() {
        assert!(is_mechanical("manifests/2024.01.02-03.04.05.manifest"));
        assert!(is_mechanical("Chunks/00/a.chunk"));
        assert!(is_mechanical("ChunksV2/00/a.chunk"));
        assert!(is_mechanical("ChunksV3/00/a.chunk"));
        assert!(is_mechanical("ChunksV4/01/a.chunk"));
        assert!(is_mechanical("ChunksV5/12/a.chunk"));
        assert!(is_mechanical("Saves/loose.chunk"));
        assert!(!is_mechanical("Saves/slot1.sav"));
        assert!(!is_mechanical("manifests.txt"));
        assert!(!is_mechanical("ChunksV6/00/a.bin"));
        assert!(!is_mechanical("ChunksV4"));
    }

    #[test]
    fn cloud_mode_reflects_manifest_presence() {
        let with = vec![
            json!({"name": "Saves/a.sav"}),
            json!({"name": "manifests/2024.05.01-10.00.00.manifest"}),
        ];
        assert_eq!(cloud_mode(&with), "manifest");
        let without = vec![json!({"name": "Saves/a.sav"}), json!({"name": "manifests.txt"})];
        assert_eq!(cloud_mode(&without), "raw");
        assert_eq!(cloud_mode(&[]), "raw");
    }

    // ---- publish, driven by an in-memory cloud ----

    use std::cell::RefCell;

    /// The newest manifest EGL would have written before this test's publish.
    const BASE_MANIFEST: &str = "manifests/2026.01.01-00.00.00.manifest";
    const BASE_STAMP_SECS: i64 = 1_767_225_600; // 2026.01.01-00.00.00 UTC
    const SERVER_TIME: i64 = 1_767_225_900; // 2026.01.01-00.05.00 UTC

    /// In-memory cloud: objects by cloud path, an ordered write log, forced failures.
    struct FakeCloud {
        objects: RefCell<HashMap<String, Vec<u8>>>,
        /// Cloud paths with this prefix fail their upload (simulated storage error).
        fail_prefix: Option<String>,
        /// The nth (1-based) upload fails no matter what it is.
        fail_nth: Option<usize>,
        /// Names that only appear in the listing (no bytes, e.g. other manifests).
        extra: Vec<String>,
        server_time: Option<i64>,
        writes: RefCell<Vec<String>>,
    }

    impl FakeCloud {
        fn new(objects: Vec<(String, Vec<u8>)>) -> FakeCloud {
            FakeCloud {
                objects: RefCell::new(objects.into_iter().collect()),
                fail_prefix: None,
                fail_nth: None,
                extra: Vec::new(),
                server_time: Some(SERVER_TIME),
                writes: RefCell::new(Vec::new()),
            }
        }

        fn snapshot_keys(&self) -> Vec<String> {
            let mut keys: Vec<String> = self.objects.borrow().keys().cloned().collect();
            keys.extend(self.extra.iter().cloned());
            keys.sort();
            keys
        }

        fn object(&self, name: &str) -> Option<Vec<u8>> {
            self.objects.borrow().get(name).cloned()
        }
    }

    impl Cloud for FakeCloud {
        fn list(
            &self,
            _access_token: &str,
            _account_id: &str,
            app_name: &str,
        ) -> Result<(Vec<Value>, Option<i64>), String> {
            let mut files = serde_json::Map::new();
            let keys = self.snapshot_keys();
            for key in &keys {
                files.insert(
                    format!("acc/{app_name}/{key}"),
                    json!({ "lastModified": "", "hash": "", "readLink": format!("fake://{key}") }),
                );
            }
            Ok((read_listing(&json!({ "files": files }), app_name), self.server_time))
        }

        fn read(&self, link: &str) -> Result<Vec<u8>, String> {
            let key = link.strip_prefix("fake://").ok_or("bad link")?;
            self.objects.borrow().get(key).cloned().ok_or_else(|| format!("no object {key}"))
        }

        fn write_links(
            &self,
            _access_token: &str,
            _account_id: &str,
            _app_name: &str,
            names: &[String],
        ) -> Result<Value, String> {
            Ok(names
                .iter()
                .map(|n| (n.clone(), Value::String(format!("fake://{n}"))))
                .collect::<serde_json::Map<String, Value>>()
                .into())
        }

        fn write(&self, link: &str, data: Vec<u8>) -> Result<(), String> {
            let key = link.strip_prefix("fake://").ok_or("bad link")?;
            let nth = self.writes.borrow().len() + 1;
            if self.fail_prefix.as_ref().map_or(false, |p| key.starts_with(p)) || self.fail_nth == Some(nth) {
                return Err(format!("forced failure for {key}"));
            }
            self.writes.borrow_mut().push(key.to_string());
            self.objects.borrow_mut().insert(key.to_string(), data);
            Ok(())
        }
    }

    /// A version-17 manifest over `files`, packed and chunked the way a publish would,
    /// together with the chunk objects it references (as cloud path → chunk file).
    fn previous_manifest(files: &[(&str, &str)]) -> (Vec<u8>, Vec<(String, Vec<u8>)>) {
        let mut chunks = Vec::new();
        let mut objects = Vec::new();
        let mut on_block = |index: usize, block: &[u8]| -> Result<Guid, String> {
            let guid: Guid = [0xA000 + index as u32, 1, 2, 3];
            let built = chunk::encode(block, &guid)?;
            let info = built.info(&guid);
            objects.push((saveformat::chunk_cloud_path(&info), built.bytes));
            chunks.push(info);
            Ok(guid)
        };
        let mut packer = saveformat::BlockPacker::new();
        for (path, content) in files {
            packer.push(path, content.as_bytes(), &mut on_block).unwrap();
        }
        let packed_files = packer.finish(&mut on_block).unwrap();
        let count = packed_files.len();
        let m = saveformat::SaveManifest {
            version: 17,
            meta: saveformat::Meta {
                data_version: 0,
                feature_level: 0,
                is_file_data: 1,
                app_id: 7,
                app_name: "App".into(),
                build_version: "2026.01.01-00.00.00".into(),
                launch_exe: String::new(),
                launch_command: String::new(),
                prereq_ids: Vec::new(),
                tail: vec![String::new(), String::new(), String::new()],
            },
            chunks,
            files: packed_files,
            links: vec![String::new(); count],
            file_flags: vec![0; count],
            custom: saveformat::CustomFields { version: 0, keys: Vec::new(), values: Vec::new() },
        };
        (saveformat::write(&m), objects)
    }

    fn temp_root(tag: &str) -> PathBuf {
        let root = std::env::temp_dir().join(format!("dd-publish-{tag}-{}", std::process::id()));
        let _ = fs::remove_dir_all(&root);
        fs::create_dir_all(root.join("cache/files")).unwrap();
        fs::create_dir_all(root.join("saves")).unwrap();
        root
    }

    fn publish_on(
        cloud: &FakeCloud,
        root: &Path,
        overlay: &[&str],
    ) -> Result<Value, PublishError> {
        let overlay: Vec<String> = overlay.iter().map(|s| s.to_string()).collect();
        publish(
            cloud,
            "token",
            "acc",
            "App",
            &root.join("cache").to_string_lossy(),
            &root.join("saves").to_string_lossy(),
            &overlay,
            BASE_MANIFEST,
        )
    }

    /// The save files the given cloud manifest describes, decoded through the regular
    /// read path (manifest::parse + chunk::decode + assemble).
    fn decoded(cloud: &FakeCloud, name: &str) -> Vec<AssembledFile> {
        let bytes = cloud.object(name).unwrap_or_else(|| panic!("{name} not in the cloud"));
        let m = manifest::parse(&bytes).unwrap();
        let store = cloud.objects.borrow();
        let mut blocks: HashMap<Guid, Vec<u8>> = HashMap::new();
        for info in &m.chunks {
            let path = saveformat::chunk_cloud_path(info);
            let raw = store.get(&path).unwrap_or_else(|| panic!("{path} not in the cloud"));
            blocks.insert(info.guid, chunk::decode(raw, info).unwrap());
        }
        assemble(&m.files, |part| {
            blocks.get(&part.guid).cloned().ok_or_else(|| "missing chunk".into())
        })
        .unwrap()
    }

    /// The text of a refusal (permanent or transient); a conflict or collision is a test bug.
    fn message_of(err: PublishError) -> String {
        match err {
            PublishError::Permanent(e) => e,
            PublishError::Failed(e) => e,
            PublishError::Conflict { latest } => panic!("unexpected conflict: {latest}"),
            PublishError::Collision(e) => panic!("unexpected collision: {e}"),
        }
    }

    /// A fake cloud seeded with the base manifest and its chunk objects.
    fn cloud_with_manifest(files: &[(&str, &str)]) -> FakeCloud {
        let (bytes, objects) = previous_manifest(files);
        let mut all = vec![(BASE_MANIFEST.to_string(), bytes)];
        all.extend(objects);
        FakeCloud::new(all)
    }

    #[test]
    fn publish_uploads_chunks_before_the_manifest_and_rewrites_the_mirror() {
        let root = temp_root("order");
        fs::write(root.join("cache/files/a.txt"), "AAA").unwrap();
        fs::write(root.join("saves/b.txt"), "BBB").unwrap();
        let cloud = cloud_with_manifest(&[("a.txt", "AAA")]);
        let answer = publish_on(&cloud, &root, &["b.txt"]).unwrap();

        let writes = cloud.writes.borrow();
        assert_eq!(writes.len(), 2, "one chunk plus one manifest: {writes:?}");
        assert!(writes[0].starts_with("ChunksV4/"), "{:?}", writes[0]);
        assert_eq!(writes[1], answer["manifest"].as_str().unwrap());
        assert_eq!(answer["manifest"], "manifests/2026.01.01-00.05.00.manifest");
        assert_eq!(answer["stamp"], "2026.01.01-00.05.00");
        assert_eq!(answer["uploaded"], json!([writes[0]]));
        assert_eq!(answer["reused"], json!([]));
        let sha_of = |bytes: &[u8]| manifest::sha1_hex(&Sha1Hasher::digest(bytes).into());
        assert_eq!(
            answer["files"],
            json!([
                { "name": "a.txt", "size": 3, "sha1": sha_of(b"AAA") },
                { "name": "b.txt", "size": 3, "sha1": sha_of(b"BBB") },
            ])
        );
        // The mirror must match the manifest that is now in the cloud.
        let files = decoded(&cloud, answer["manifest"].as_str().unwrap());
        assert_eq!(files.iter().map(|f| f.name.as_str()).collect::<Vec<_>>(), ["a.txt", "b.txt"]);
        assert_eq!(files[0].content, b"AAA");
        assert_eq!(files[1].content, b"BBB");
        assert_eq!(fs::read(root.join("cache/files/a.txt")).unwrap(), b"AAA");
        assert_eq!(fs::read(root.join("cache/files/b.txt")).unwrap(), b"BBB");
        let no_staging = fs::read_dir(root.join("cache"))
            .unwrap()
            .flatten()
            .all(|e| !e.file_name().to_string_lossy().starts_with("files.new-"));
        assert!(no_staging, "successful publishes must clean their staging folder");
        let _ = fs::remove_dir_all(&root);
    }

    #[test]
    fn publish_stamps_from_the_device_clock_without_an_http_date() {
        let root = temp_root("devclock");
        fs::write(root.join("cache/files/a.txt"), "AAA").unwrap();
        let mut cloud = cloud_with_manifest(&[("a.txt", "AAA")]);
        cloud.server_time = None;
        let answer = publish_on(&cloud, &root, &[]).unwrap();
        let name = answer["manifest"].as_str().unwrap();
        assert!(name > BASE_MANIFEST, "{name} must be newer than {BASE_MANIFEST}");
        assert_eq!(name, format!("manifests/{}.manifest", answer["stamp"].as_str().unwrap()));
        // The stamp must really have come from the device clock, not from the
        // strictly-newer fallback on some defaulted value.
        let stamp_secs = timefmt::stamp_seconds(answer["stamp"].as_str().unwrap()).unwrap();
        let now = device_seconds();
        assert!(
            (stamp_secs - now).abs() <= 5,
            "stamp {stamp_secs} must track the device clock (now {now})"
        );
        let _ = fs::remove_dir_all(&root);
    }

    #[test]
    fn stamps_stay_strictly_newer_than_the_latest_manifest() {
        // A clock behind the newest manifest bumps to latest + 1 second; equal is not
        // strictly newer either.
        assert_eq!(next_stamp(Some(1_000), BASE_MANIFEST), "2026.01.01-00.00.01");
        assert_eq!(next_stamp(Some(BASE_STAMP_SECS), BASE_MANIFEST), "2026.01.01-00.00.01");
        // Ahead of the latest, the server clock wins as-is.
        assert_eq!(next_stamp(Some(SERVER_TIME), BASE_MANIFEST), "2026.01.01-00.05.00");
        // A newest name that is no timestamp never holds the stamp back.
        assert_eq!(next_stamp(Some(SERVER_TIME), "manifests/backup.manifest"), "2026.01.01-00.05.00");
    }

    #[test]
    fn a_matching_chunk_is_reused_and_not_uploaded() {
        let root = temp_root("reuse");
        fs::write(root.join("cache/files/a.txt"), "AAA").unwrap();
        let cloud = cloud_with_manifest(&[("a.txt", "AAA")]);
        let base = saveformat::read(&cloud.object(BASE_MANIFEST).unwrap()).unwrap();
        let old_path = saveformat::chunk_cloud_path(&base.chunks[0]);
        let old_bytes = cloud.object(&old_path).unwrap();
        let answer = publish_on(&cloud, &root, &[]).unwrap();
        assert_eq!(answer["reused"], json!([old_path]));
        assert_eq!(answer["uploaded"], json!([]));
        assert_eq!(*cloud.writes.borrow(), vec![answer["manifest"].as_str().unwrap().to_string()]);
        assert_eq!(cloud.object(&old_path).unwrap(), old_bytes, "the chunk must stay untouched");
        let _ = fs::remove_dir_all(&root);
    }

    #[test]
    fn a_chunk_upload_failure_leaves_the_manifest_unuploaded() {
        let root = temp_root("fail");
        fs::write(root.join("cache/files/a.txt"), "AAA").unwrap();
        fs::write(root.join("saves/b.txt"), "BBB").unwrap();
        let mut cloud = cloud_with_manifest(&[("a.txt", "AAA")]);
        cloud.fail_prefix = Some("ChunksV4/".to_string());
        let err = publish_on(&cloud, &root, &["b.txt"]).unwrap_err();
        assert!(matches!(err, PublishError::Failed(_)), "{err:?}");
        let wrote_manifest = cloud
            .writes
            .borrow()
            .iter()
            .any(|p| p.starts_with("manifests/"));
        assert!(!wrote_manifest, "the manifest must not be uploaded: {:?}", cloud.writes.borrow());
        assert!(cloud.object("manifests/2026.01.01-00.05.00.manifest").is_none());
        let _ = fs::remove_dir_all(&root);
    }

    #[test]
    fn a_moved_cloud_manifest_is_reported_as_conflict() {
        let root = temp_root("conflict");
        fs::write(root.join("cache/files/a.txt"), "AAA").unwrap();
        let mut cloud = cloud_with_manifest(&[("a.txt", "AAA")]);
        cloud.extra = vec!["manifests/2026.01.02-00.00.00.manifest".to_string()];
        let err = publish_on(&cloud, &root, &[]).unwrap_err();
        assert_eq!(
            err,
            PublishError::Conflict { latest: "manifests/2026.01.02-00.00.00.manifest".into() }
        );
        assert!(cloud.writes.borrow().is_empty(), "a conflict must write nothing");
        let _ = fs::remove_dir_all(&root);
    }

    #[test]
    fn a_missing_overlay_file_writes_nothing() {
        let root = temp_root("missing");
        fs::write(root.join("cache/files/a.txt"), "AAA").unwrap();
        let cloud = cloud_with_manifest(&[("a.txt", "AAA")]);
        let before = cloud.snapshot_keys();
        // Missing files are transient: a plain retriable failure, not a permanent one.
        let err = publish_on(&cloud, &root, &["b.txt"]).unwrap_err();
        assert!(matches!(err, PublishError::Failed(ref e) if e.contains("b.txt")), "{err:?}");
        assert_eq!(cloud.snapshot_keys(), before, "nothing may be written to the cloud");
        assert!(cloud.writes.borrow().is_empty());
        assert_eq!(fs::read(root.join("cache/files/a.txt")).unwrap(), b"AAA");
        let _ = fs::remove_dir_all(&root);
    }

    #[test]
    fn publish_refuses_a_path_that_escapes_the_save_dir() {
        let root = temp_root("escape");
        fs::write(root.join("cache/files/a.txt"), "AAA").unwrap();
        fs::write(root.join("outside.txt"), "secret").unwrap();
        let cloud = cloud_with_manifest(&[("a.txt", "AAA")]);
        // A `..` name is refused outright.
        let before = cloud.snapshot_keys();
        let err = publish_on(&cloud, &root, &["../outside.txt"]).unwrap_err();
        let message = message_of(err);
        assert!(message.contains("unsafe"), "{message}");
        // A symlink inside saveDir pointing outside it must not slip through either.
        #[cfg(unix)]
        std::os::unix::fs::symlink(root.join("outside.txt"), root.join("saves/link.sav")).unwrap();
        #[cfg(unix)]
        {
            let err = publish_on(&cloud, &root, &["link.sav"]).unwrap_err();
            let message = message_of(err);
            assert!(message.contains("escapes"), "{message}");
        }
        assert_eq!(cloud.snapshot_keys(), before);
        assert!(cloud.writes.borrow().is_empty());
        let _ = fs::remove_dir_all(&root);
    }

    #[test]
    fn publish_keeps_files_the_overlay_does_not_mention() {
        let root = temp_root("keep");
        fs::create_dir_all(root.join("cache/files/C")).unwrap();
        fs::write(root.join("cache/files/a.txt"), "AAA").unwrap();
        fs::write(root.join("cache/files/C/c.txt"), "CCC").unwrap();
        fs::write(root.join("cache/files/stale.txt"), "old").unwrap();
        let cloud = cloud_with_manifest(&[("a.txt", "AAA"), ("C/c.txt", "CCC")]);
        let answer = publish_on(&cloud, &root, &[]).unwrap();
        let names: Vec<&str> = answer["files"]
            .as_array()
            .unwrap()
            .iter()
            .map(|f| f["name"].as_str().unwrap())
            .collect();
        assert_eq!(names, ["a.txt", "C/c.txt"]);
        let files = decoded(&cloud, answer["manifest"].as_str().unwrap());
        assert_eq!(files.iter().map(|f| f.name.as_str()).collect::<Vec<_>>(), ["a.txt", "C/c.txt"]);
        assert_eq!(files[1].content, b"CCC");
        // The mirror is pruned to exactly the new manifest.
        assert_eq!(fs::read(root.join("cache/files/a.txt")).unwrap(), b"AAA");
        assert_eq!(fs::read(root.join("cache/files/C/c.txt")).unwrap(), b"CCC");
        assert!(!root.join("cache/files/stale.txt").exists());
        let _ = fs::remove_dir_all(&root);
    }

    #[test]
    fn publish_refuses_an_unreadable_previous_manifest() {
        let root = temp_root("unreadable");
        fs::write(root.join("cache/files/a.txt"), "AAA").unwrap();
        let cloud = cloud_with_manifest(&[("a.txt", "AAA")]);
        if let Some(entry) = cloud.objects.borrow_mut().get_mut(BASE_MANIFEST) {
            *entry = b"garbage".to_vec();
        }
        let err = publish_on(&cloud, &root, &[]).unwrap_err();
        let message = message_of(err);
        assert!(message.contains("cannot be read"), "{message}");
        assert!(cloud.writes.borrow().is_empty());
        let _ = fs::remove_dir_all(&root);
    }

    #[test]
    fn a_previous_file_missing_everywhere_aborts_with_no_writes() {
        // The mirror and the save dir both lack a file the previous manifest still lists:
        // dropping it silently would lose data, so the publish must abort untouched. The
        // failure is transient (an incomplete mirror fills in on the next snapshot), so
        // it stays retriable rather than permanent.
        let root = temp_root("gone");
        let cloud = cloud_with_manifest(&[("a.txt", "AAA")]);
        let before = cloud.snapshot_keys();
        let err = publish_on(&cloud, &root, &[]).unwrap_err();
        assert!(matches!(err, PublishError::Failed(ref e) if e.contains("a.txt")), "{err:?}");
        assert_eq!(cloud.snapshot_keys(), before);
        assert!(cloud.writes.borrow().is_empty());
        let _ = fs::remove_dir_all(&root);
    }

    #[test]
    fn a_file_missing_from_the_mirror_is_read_from_the_save_dir() {
        let root = temp_root("fallback");
        fs::write(root.join("saves/a.txt"), "ZZZ").unwrap();
        let cloud = cloud_with_manifest(&[("a.txt", "AAA")]);
        let answer = publish_on(&cloud, &root, &[]).unwrap();
        let files = decoded(&cloud, answer["manifest"].as_str().unwrap());
        assert_eq!(files[0].content, b"ZZZ", "the saveDir copy must be packed");
        assert_eq!(fs::read(root.join("cache/files/a.txt")).unwrap(), b"ZZZ");
        let _ = fs::remove_dir_all(&root);
    }

    #[test]
    fn a_later_chunk_failure_still_leaves_no_manifest() {
        // Two fresh chunks, the second PUT fails: the first chunk may be an orphan, but
        // no manifest may reach the cloud.
        let root = temp_root("late-fail");
        fs::write(root.join("cache/files/a.txt"), "AAA").unwrap();
        let big: Vec<u8> = (0..chunk::CHUNK_WINDOW + 16).map(|i| (((i % 251) * 7) % 256) as u8).collect();
        fs::write(root.join("saves/big.dat"), &big).unwrap();
        let mut cloud = cloud_with_manifest(&[("a.txt", "AAA")]);
        cloud.fail_nth = Some(2);
        let err = publish_on(&cloud, &root, &["big.dat"]).unwrap_err();
        assert!(matches!(err, PublishError::Failed(_)), "{err:?}");
        let writes = cloud.writes.borrow();
        assert!(writes.iter().all(|p| !p.starts_with("manifests/")), "{writes:?}");
        assert!(cloud.object("manifests/2026.01.01-00.05.00.manifest").is_none());
        let _ = fs::remove_dir_all(&root);
    }

    #[test]
    fn a_manifest_upload_failure_keeps_the_cloud_manifest_unchanged() {
        let root = temp_root("manifest-fail");
        fs::write(root.join("cache/files/a.txt"), "AAA").unwrap();
        fs::write(root.join("saves/b.txt"), "BBB").unwrap();
        let mut cloud = cloud_with_manifest(&[("a.txt", "AAA")]);
        cloud.fail_prefix = Some("manifests/".to_string());
        let err = publish_on(&cloud, &root, &["b.txt"]).unwrap_err();
        assert!(matches!(err, PublishError::Failed(_)), "{err:?}");
        let writes = cloud.writes.borrow();
        assert!(writes.iter().all(|p| !p.starts_with("manifests/")), "{writes:?}");
        assert!(cloud.object("manifests/2026.01.01-00.05.00.manifest").is_none());
        let _ = fs::remove_dir_all(&root);
    }

    #[test]
    fn a_failed_mirror_update_still_reports_the_published_manifest() {
        // The manifest is already up when the mirror breaks: here the files folder is
        // made read-only so renaming the overlay's new file into it fails. The publish
        // must still succeed, leave no staging folder behind, and the mirror keeps its
        // old content — the next snapshot rebuilds it.
        let root = temp_root("mirror-fail");
        let files_dir = root.join("cache/files");
        fs::write(files_dir.join("a.txt"), "AAA").unwrap();
        fs::write(root.join("saves/b.txt"), "BBB").unwrap();
        let mut perms = fs::metadata(&files_dir).unwrap().permissions();
        perms.set_readonly(true);
        fs::set_permissions(&files_dir, perms).unwrap();
        let cloud = cloud_with_manifest(&[("a.txt", "AAA")]);
        let answer = publish_on(&cloud, &root, &["b.txt"]);
        // Let the temp tree be deletable again.
        let mut perms = fs::metadata(&files_dir).unwrap().permissions();
        perms.set_readonly(false);
        fs::set_permissions(&files_dir, perms).unwrap();
        let answer = answer.unwrap();
        assert!(cloud.object(answer["manifest"].as_str().unwrap()).is_some());
        assert_eq!(answer["files"].as_array().unwrap().len(), 2);
        assert_eq!(fs::read(files_dir.join("a.txt")).unwrap(), b"AAA");
        assert!(!files_dir.join("b.txt").exists(), "the failed refresh must not have landed");
        let no_staging = fs::read_dir(root.join("cache"))
            .unwrap()
            .flatten()
            .all(|e| !e.file_name().to_string_lossy().starts_with("files.new-"));
        assert!(no_staging, "the staging folder must be cleaned up");
        let _ = fs::remove_dir_all(&root);
    }

    #[test]
    fn an_overlay_with_case_only_duplicates_is_refused() {
        // Two overlay names differing only in case would fight over one slot in the
        // packed stream depending on the caller's listing order; refuse instead.
        let root = temp_root("case-dup");
        fs::write(root.join("saves/b.txt"), "BBB").unwrap();
        fs::write(root.join("saves/B.txt"), "upper").unwrap();
        let cloud = cloud_with_manifest(&[("a.txt", "AAA")]);
        let before = cloud.snapshot_keys();
        let err = publish_on(&cloud, &root, &["b.txt", "B.txt"]).unwrap_err();
        assert!(matches!(err, PublishError::Collision(ref e) if e.contains("twice")), "{err:?}");
        assert_eq!(cloud.snapshot_keys(), before);
        assert!(cloud.writes.borrow().is_empty());
        let _ = fs::remove_dir_all(&root);
    }

    #[test]
    fn the_merged_file_set_is_screened_for_names_before_anything_is_uploaded() {
        // The non-ASCII name comes with the previous manifest (not via the overlay), and
        // its chunk object is gone: the merged set's name screening must refuse before
        // the other file's fresh chunk is uploaded as an orphan.
        let root = temp_root("screen");
        let big: Vec<u8> = (0..chunk::CHUNK_WINDOW + 16).map(|i| (((i % 251) * 7) % 256) as u8).collect();
        fs::write(root.join("saves/0big.dat"), &big).unwrap();
        let cloud = cloud_with_manifest(&[("0big.dat", "AAA")]);
        let base = saveformat::read(&cloud.object(BASE_MANIFEST).unwrap()).unwrap();
        let old_path = saveformat::chunk_cloud_path(&base.chunks[0]);
        cloud.objects.borrow_mut().remove(&old_path);
        let mut dup = base.clone();
        dup.files.push(FileEntry {
            path: "caf\u{e9}.txt".into(),
            sha1: Some([0; 20]),
            tags: Vec::new(),
            parts: Vec::new(),
            size: 0,
        });
        dup.links.push(String::new());
        dup.file_flags.push(0);
        cloud.objects.borrow_mut().insert(BASE_MANIFEST.to_string(), saveformat::write(&dup));
        let before = cloud.snapshot_keys();
        let err = publish_on(&cloud, &root, &["0big.dat"]).unwrap_err();
        assert!(
            matches!(err, PublishError::Permanent(ref e) if e.contains("ASCII")),
            "{err:?}"
        );
        assert_eq!(cloud.snapshot_keys(), before);
        assert!(cloud.writes.borrow().is_empty());
        let _ = fs::remove_dir_all(&root);
    }

    /// The case-only rename rule needs a folder that keeps both spellings as separate files;
    /// the default volumes of macOS and Windows fold them into one, where the scenario cannot
    /// even be built (run with `--ignored` on a case-sensitive volume; CI's Linux runs it).
    #[test]
    #[cfg_attr(any(target_os = "macos", target_os = "windows"), ignore = "the temp volume folds case")]
    fn a_case_only_rename_is_refused_while_both_spellings_exist_then_publishes_once_the_old_one_is_gone() {
        let root = temp_root("case-rename");
        fs::create_dir_all(root.join("cache/files/Save")).unwrap();
        fs::create_dir_all(root.join("saves/Save")).unwrap();
        fs::create_dir_all(root.join("saves/SAVE")).unwrap();
        fs::write(root.join("cache/files/Save/x.sav"), "OLD").unwrap();
        fs::write(root.join("saves/Save/x.sav"), "OLD").unwrap();
        fs::write(root.join("saves/SAVE/x.sav"), "NEW").unwrap();
        let cloud = cloud_with_manifest(&[("Save/x.sav", "OLD")]);
        // The game renamed its save folder but the old spelling is still there: who owns the
        // newer content is ambiguous, so the publish is refused instead of picking a winner.
        let before = cloud.snapshot_keys();
        let err = publish_on(&cloud, &root, &["SAVE/x.sav"]).unwrap_err();
        assert!(matches!(err, PublishError::Collision(ref e) if e.contains("only in case")), "{err:?}");
        assert_eq!(cloud.snapshot_keys(), before);
        assert!(cloud.writes.borrow().is_empty());
        // The old spelling gone, the same publish is just the rename landing: the file set
        // and the mirror follow it.
        fs::remove_file(root.join("saves/Save/x.sav")).unwrap();
        let answer = publish_on(&cloud, &root, &["SAVE/x.sav"]).unwrap();
        let names: Vec<&str> = answer["files"]
            .as_array()
            .unwrap()
            .iter()
            .map(|f| f["name"].as_str().unwrap())
            .collect();
        assert_eq!(names, ["SAVE/x.sav"], "the old spelling must be gone from the file set");
        let files = decoded(&cloud, answer["manifest"].as_str().unwrap());
        assert_eq!(files.iter().map(|f| f.name.as_str()).collect::<Vec<_>>(), names);
        assert_eq!(files[0].content, b"NEW");
        assert_eq!(fs::read(root.join("cache/files/SAVE/x.sav")).unwrap(), b"NEW");
        assert!(!root.join("cache/files/Save/x.sav").exists());
        let _ = fs::remove_dir_all(&root);
    }

    #[test]
    fn publish_orders_files_case_insensitively() {
        // Overlay order follows the directory listing, not the packed stream: the manifest
        // must still come out in case-insensitive name order.
        let root = temp_root("order2");
        fs::create_dir_all(root.join("cache/files/C")).unwrap();
        fs::write(root.join("cache/files/b.txt"), "B").unwrap();
        fs::write(root.join("cache/files/C/d.txt"), "D").unwrap();
        fs::create_dir_all(root.join("saves/c")).unwrap();
        fs::write(root.join("saves/c/e.txt"), "E").unwrap();
        fs::write(root.join("saves/a.txt"), "A").unwrap();
        let cloud = cloud_with_manifest(&[("b.txt", "B"), ("C/d.txt", "D")]);
        let answer = publish_on(&cloud, &root, &["c/e.txt", "a.txt"]).unwrap();
        let names: Vec<&str> = answer["files"]
            .as_array()
            .unwrap()
            .iter()
            .map(|f| f["name"].as_str().unwrap())
            .collect();
        assert_eq!(names, ["a.txt", "b.txt", "C/d.txt", "c/e.txt"]);
        let files = decoded(&cloud, answer["manifest"].as_str().unwrap());
        assert_eq!(files.iter().map(|f| f.name.as_str()).collect::<Vec<_>>(), names);
        assert_eq!(files[0].content, b"A");
        let _ = fs::remove_dir_all(&root);
    }

    #[test]
    fn two_identical_blocks_share_one_chunk() {
        let root = temp_root("same-blocks");
        fs::write(root.join("cache/files/seed.txt"), "SEED").unwrap();
        let block: Vec<u8> = (0..chunk::CHUNK_WINDOW).map(|i| (((i % 251) * 7) % 256) as u8).collect();
        fs::write(root.join("saves/f1.dat"), &block).unwrap();
        fs::write(root.join("saves/f2.dat"), &block).unwrap();
        let cloud = cloud_with_manifest(&[("seed.txt", "SEED")]);
        let answer = publish_on(&cloud, &root, &["f1.dat", "f2.dat"]).unwrap();
        assert_eq!(
            answer["uploaded"].as_array().unwrap().len(),
            1,
            "the second identical block must reuse the first one's chunk"
        );
        let writes = cloud.writes.borrow();
        assert_eq!(writes.iter().filter(|p| p.starts_with("ChunksV4/")).count(), 1, "{writes:?}");
        let m = saveformat::read(&cloud.object(answer["manifest"].as_str().unwrap()).unwrap()).unwrap();
        // The chunk list stays deduplicated: two identical blocks, one fresh entry, plus
        // seed.txt's reused chunk — never the same chunk listed twice.
        assert_eq!(m.chunks.len(), 2, "one fresh chunk plus the reused seed chunk");
        let files = decoded(&cloud, answer["manifest"].as_str().unwrap());
        assert_eq!(files.len(), 3);
        assert_eq!(
            files.iter().map(|f| f.name.as_str()).collect::<Vec<_>>(),
            ["f1.dat", "f2.dat", "seed.txt"],
            "packing order"
        );
        assert_eq!(files[0].content, block);
        assert_eq!(files[1].content, block);
        assert_eq!(files[2].content, b"SEED");
        // Both identical files' parts point at the single fresh chunk's GUID.
        for f in &m.files[..2] {
            assert_eq!(f.parts.len(), 1);
            assert_eq!(f.parts[0].guid, m.chunks[0].guid, "{}", f.path);
        }
        assert_ne!(m.chunks[1].guid, m.chunks[0].guid, "seed.txt still reuses its own chunk");
        assert_eq!(m.chunks[1].rolling_hash, chunk::rolling_hash(&{
            let mut seed_block = b"SEED".to_vec();
            seed_block.resize(chunk::CHUNK_WINDOW, 0);
            seed_block
        }));
        let _ = fs::remove_dir_all(&root);
    }

    #[test]
    fn a_pruned_cloud_chunk_is_reuploaded_instead_of_reused() {
        // EGL prunes chunks of older manifests: when the matching chunk is no longer in
        // the cloud, the block must be repacked and uploaded fresh — a manifest that
        // references a pruned chunk would be undownloadable.
        let root = temp_root("pruned");
        fs::write(root.join("cache/files/a.txt"), "AAA").unwrap();
        let cloud = cloud_with_manifest(&[("a.txt", "AAA")]);
        let base = saveformat::read(&cloud.object(BASE_MANIFEST).unwrap()).unwrap();
        let old_path = saveformat::chunk_cloud_path(&base.chunks[0]);
        cloud.objects.borrow_mut().remove(&old_path);
        let answer = publish_on(&cloud, &root, &[]).unwrap();
        assert_eq!(answer["reused"], json!([]), "the pruned chunk must not be reused");
        assert_eq!(answer["uploaded"].as_array().unwrap().len(), 1);
        let files = decoded(&cloud, answer["manifest"].as_str().unwrap());
        assert_eq!(files[0].content, b"AAA");
        let _ = fs::remove_dir_all(&root);
    }

    #[test]
    fn a_read_only_cache_dir_fails_before_anything_is_uploaded() {
        let root = temp_root("probe");
        fs::write(root.join("saves/a.txt"), "AAA").unwrap();
        fs::write(root.join("saves/b.txt"), "BBB").unwrap();
        let mut perms = fs::metadata(root.join("cache")).unwrap().permissions();
        perms.set_readonly(true);
        fs::set_permissions(root.join("cache"), perms).unwrap();
        let cloud = cloud_with_manifest(&[("a.txt", "AAA")]);
        let result = publish_on(&cloud, &root, &["b.txt"]);
        // Let the temp tree be deletable again.
        let mut perms = fs::metadata(root.join("cache")).unwrap().permissions();
        perms.set_readonly(false);
        fs::set_permissions(root.join("cache"), perms).unwrap();
        let message = match result.unwrap_err() {
            PublishError::Failed(e) => e,
            other => panic!("{other:?}"),
        };
        assert!(message.contains("not writable"), "{message}");
        assert!(cloud.writes.borrow().is_empty());
        let no_probe = fs::read_dir(root.join("cache"))
            .unwrap()
            .flatten()
            .all(|e| !e.file_name().to_string_lossy().starts_with("publish-probe-"));
        assert!(no_probe, "the probe must clean up after itself");
        let _ = fs::remove_dir_all(&root);
    }
}
