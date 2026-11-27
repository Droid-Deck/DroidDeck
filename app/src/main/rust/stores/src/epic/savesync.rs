use std::collections::{HashMap, HashSet};
use std::fs;
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicU64, Ordering};
use std::time::Duration;

use serde_json::{json, Value};
use sha1::{Digest, Sha1 as Sha1Hasher};

use super::chunk;
use super::manifest::{self, ChunkInfo, FileEntry, Guid, PartRef, Sha1};
use super::net::{self, Body, Request};

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
    let url = base(account_id, app_name);
    let reply = net::send(Request::get(&url).bearer(access_token))?;
    if reply.status == 404 {
        return Ok(Vec::new());
    }
    if !reply.ok() {
        return Err(format!("Epic cloud list HTTP {}", reply.status));
    }
    let answer = reply.json().ok_or("Epic cloud list could not be read")?;
    Ok(read_listing(&answer, app_name))
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

/// Manifest names must be clean relative paths before anything is written under the cache.
pub fn safe_save_path(path: &str) -> bool {
    !path.is_empty()
        && !path.starts_with('/')
        && !path.contains('\\')
        && path.split('/').all(|seg| !seg.is_empty() && seg != "." && seg != "..")
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

    #[test]
    fn save_paths_are_screened() {
        for good in ["Saves/slot1.sav", "a/b/c.bin", "x.sav"] {
            assert!(safe_save_path(good), "{good}");
        }
        for bad in ["", "/abs", "../up", "a/../b", "a//b", "./x", "back\\slash"] {
            assert!(!safe_save_path(bad), "{bad}");
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
}
