use std::collections::HashMap;
use std::io::{Read, Seek, SeekFrom, Write};
use std::path::{Component, Path, PathBuf};
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::Mutex;
use std::time::{Duration, Instant};

use futures_util::stream::{self, StreamExt};
use serde_json::{json, Value as Json};
use sha1::{Digest, Sha1};

use super::demux::Demux;
use super::manifest::{self, File};
use super::service::{Downloads, Ownership};
use crate::amazon::install::{Events, Stop};

const SIGN_BATCH: usize = 200;
const WINDOW: usize = 16;
const TRIES: usize = 4;

pub struct Request {
    pub ticket: String,
    pub session_id: String,
    pub product_id: u32,
    pub manifest: String,
    pub folder: PathBuf,
    pub language: String,
}

impl Request {
    pub fn from_json(v: &Json) -> Result<Self, String> {
        let s = |k: &str| v.get(k).and_then(|x| x.as_str()).unwrap_or("").to_string();
        let product_id = v.get("productId").and_then(|x| x.as_u64()).ok_or("no product id")? as u32;
        let folder = PathBuf::from(s("folder"));
        if s("ticket").is_empty() || folder.as_os_str().is_empty() {
            return Err("The install request is incomplete".into());
        }
        Ok(Request { ticket: s("ticket"), session_id: s("sessionId"), product_id, manifest: s("manifest"), folder, language: s("language") })
    }
}

pub struct Done {
    pub files: usize,
    pub bytes: u64,
    pub chunks: Vec<manifest::Chunk>,
    pub setup: Json,
}

pub fn chunks_json(chunks: &[manifest::Chunk]) -> Json {
    Json::Array(chunks.iter().map(|c| json!({ "id": c.id, "tags": c.tags })).collect())
}

pub fn setup_json(m: &manifest::Manifest) -> Json {
    json!({
        "installRuns": m.install_runs.iter().map(|(exe, args, what)| json!({ "exe": exe, "arguments": args, "description": what })).collect::<Vec<_>>(),
        "registry": m.registry.iter().map(|(key, values)| json!({ "key": key, "values": values.iter().map(|(n, v)| json!({ "name": n, "value": v })).collect::<Vec<_>>() })).collect::<Vec<_>>(),
    })
}

impl Done {
    pub fn to_json(&self) -> Json {
        json!({ "files": self.files, "bytes": self.bytes, "chunks": chunks_json(&self.chunks), "setup": self.setup.clone() })
    }
}

pub fn safe_path(root: &Path, name: &str) -> Result<PathBuf, String> {
    let rel = Path::new(name.trim_start_matches('/'));
    if rel.components().any(|c| !matches!(c, Component::Normal(_))) {
        return Err(format!("The manifest names an unsafe path: {name}"));
    }
    Ok(root.join(rel))
}

fn sha1(b: &[u8]) -> Vec<u8> {
    Sha1::digest(b).to_vec()
}

/// The slice as served and as unpacked must both match the manifest, so a bad proxy or a truncated body never lands in the game.
pub fn checked(method: u64, raw: &[u8], s: &manifest::Slice) -> Result<Vec<u8>, String> {
    if let Some(n) = s.download_size {
        if raw.len() as u32 != n {
            return Err(format!("a slice is {} bytes, the manifest says {n}", raw.len()));
        }
    }
    if !s.download_sha1.is_empty() && sha1(raw) != s.download_sha1 {
        return Err("a slice failed its download checksum".into());
    }
    let out = manifest::decompress(method, raw)?;
    if s.size != 0 && out.len() as u32 != s.size {
        return Err(format!("a slice unpacked to {} bytes, the manifest says {}", out.len(), s.size));
    }
    if s.content_sha1.len() == 20 && sha1(&out) != s.content_sha1 {
        return Err("a slice failed its content checksum".into());
    }
    Ok(out)
}

/// Whether a file of the right size already holds every slice the manifest lists for it; a file that cannot be checked is fetched again.
fn already_installed(path: &Path, f: &File) -> bool {
    if f.slices.iter().any(|s| s.content_sha1.len() != 20 || s.size == 0) {
        return false;
    }
    let Ok(mut file) = std::fs::File::open(path) else { return false };
    let mut buf = Vec::new();
    f.slices.iter().all(|s| {
        buf.resize(s.size as usize, 0);
        file.seek(SeekFrom::Start(s.offset)).is_ok() && file.read_exact(&mut buf).is_ok() && sha1(&buf) == s.content_sha1
    })
}

fn slice_hash(s: &manifest::Slice) -> String {
    manifest::hex_upper(if s.download_sha1.is_empty() { &s.content_sha1 } else { &s.download_sha1 })
}

struct Job {
    file: usize,
    slice: usize,
    path: String,
}

async fn fetch(client: &reqwest::Client, urls: &[String]) -> Result<Vec<u8>, String> {
    let mut last = String::from("no URL");
    for attempt in 0..TRIES {
        for u in urls {
            match client.get(u).send().await {
                Ok(r) if r.status().is_success() => match r.bytes().await {
                    Ok(b) => return Ok(b.to_vec()),
                    Err(e) => last = e.to_string(),
                },
                Ok(r) => last = format!("HTTP {}", r.status().as_u16()),
                Err(e) => last = e.to_string(),
            }
        }
        tokio::time::sleep(Duration::from_millis(500 << attempt)).await;
    }
    Err(format!("a slice could not be downloaded ({last})"))
}

/// A Demux login holding a download_service connection for one product. Ubisoft drops idle sockets
/// while slices download, so signing reconnects and retries when the connection is gone.
struct Session {
    d: Demux,
    dl: Downloads,
}

const RECONNECTS: usize = 4;

impl Session {
    /// Opens a session, retrying a few times: Demux drops a connection now and then right after another closes.
    async fn open(req: &Request) -> Result<(Self, Vec<super::service::Owned>), String> {
        let mut last = String::new();
        for attempt in 0..3u32 {
            if attempt > 0 {
                tokio::time::sleep(Duration::from_secs(2 << attempt)).await;
            }
            match Self::open_once(req).await {
                Ok(s) => return Ok(s),
                Err(e) => last = e,
            }
        }
        Err(last)
    }

    async fn open_once(req: &Request) -> Result<(Self, Vec<super::service::Owned>), String> {
        let mut d = Demux::connect(&req.ticket).await?;
        let (mut own, owned) = Ownership::open(&mut d, &req.ticket, &req.session_id).await?;
        let token = own.token(&mut d, req.product_id).await?;
        let dl = Downloads::open(&mut d, &token).await?;
        Ok((Session { d, dl }, owned))
    }

    async fn sign(&mut self, req: &Request, paths: &[String], events: &dyn Events) -> Result<Vec<Vec<String>>, String> {
        let mut last = String::new();
        for attempt in 0..=RECONNECTS {
            if attempt > 0 {
                events.log(&format!("ubisoft: reconnecting after: {last}"));
                tokio::time::sleep(Duration::from_millis(500 << attempt.min(4))).await;
                match Session::open(req).await {
                    Ok((fresh, _)) => *self = fresh,
                    Err(e) => {
                        last = e;
                        continue;
                    }
                }
            }
            match self.dl.sign(&mut self.d, req.product_id, paths).await {
                Ok(urls) => return Ok(urls),
                Err(e) if e.contains("does not own") => return Err(e),
                Err(e) => last = e,
            }
        }
        Err(last)
    }
}

/// The manifest of an install, without downloading the game.
pub fn chunks(req: &Request) -> Result<manifest::Manifest, String> {
    let rt = tokio::runtime::Builder::new_current_thread().enable_all().build().map_err(|e| e.to_string())?;
    rt.block_on(async {
        let (mut sess, owned) = Session::open(req).await?;
        let manifest_id = if req.manifest.is_empty() {
            owned.iter().find(|o| o.product_id == req.product_id).map(|o| o.manifest.clone()).unwrap_or_default()
        } else {
            req.manifest.clone()
        };
        if manifest_id.is_empty() {
            return Err("Ubisoft lists no download for this game".to_string());
        }
        let client = reqwest::Client::builder().timeout(Duration::from_secs(120)).build().map_err(|e| e.to_string())?;
        struct Quiet;
        impl Events for Quiet {
            fn log(&self, _: &str) {}
            fn status(&self, _: &str) {}
            fn checking(&self, _: u64, _: u64, _: u32, _: u32) {}
            fn sizes(&self, _: u64, _: u64) {}
            fn progress(&self, _: u64, _: u64, _: u32, _: u32) {}
            fn before_replace(&self) -> bool { false }
        }
        let urls = sess.sign(req, &[format!("manifests/{manifest_id}.manifest")], &Quiet).await?;
        let raw = fetch(&client, urls.first().map(|v| v.as_slice()).unwrap_or_default()).await?;
        let language = if req.language.is_empty() { "en-US" } else { req.language.as_str() };
        let mut owned_ids: Vec<u32> = owned.iter().filter(|o| o.owned).map(|o| o.product_id).collect();
        owned_ids.push(req.product_id);
        Ok(manifest::parse_owned(&manifest::decode(&raw)?, language, &owned_ids)?)
    })
}

pub fn run(req: &Request, cancel: &AtomicBool, events: &dyn Events) -> Result<Done, Stop> {
    let rt = tokio::runtime::Builder::new_current_thread().enable_all().build().map_err(|e| Stop::Failed(e.to_string()))?;
    rt.block_on(install(req, cancel, events))
}

async fn install(req: &Request, cancel: &AtomicBool, events: &dyn Events) -> Result<Done, Stop> {
    let fail = Stop::Failed;
    events.status("Connecting to Ubisoft");
    let (mut sess, owned) = Session::open(req).await.map_err(fail)?;
    let manifest_id = if req.manifest.is_empty() {
        owned.iter().find(|o| o.product_id == req.product_id).map(|o| o.manifest.clone()).unwrap_or_default()
    } else {
        req.manifest.clone()
    };
    if manifest_id.is_empty() {
        return Err(Stop::Failed("Ubisoft lists no download for this game".into()));
    }
    let client = reqwest::Client::builder().timeout(Duration::from_secs(120)).build().map_err(|e| Stop::Failed(e.to_string()))?;

    events.status("Reading the game's file list");
    let urls = sess.sign(req, &[format!("manifests/{manifest_id}.manifest")], events).await.map_err(fail)?;
    let raw = fetch(&client, urls.first().map(|v| v.as_slice()).unwrap_or_default()).await.map_err(fail)?;
    let decoded = manifest::decode(&raw).map_err(fail)?;
    let language = if req.language.is_empty() { "en-US" } else { req.language.as_str() };
    let mut owned_ids: Vec<u32> = owned.iter().filter(|o| o.owned).map(|o| o.product_id).collect();
    owned_ids.push(req.product_id);
    let m = manifest::parse_owned(&decoded, language, &owned_ids).map_err(fail)?;
    events.log(&format!("ubisoft: manifest {manifest_id} files={} chunks={} skipped={} compression={}", m.files.len(), m.chunks.len(), m.skipped.len(), m.compression));
    for name in &m.skipped {
        if let Ok(path) = safe_path(&req.folder, name) {
            if path.is_file() && std::fs::remove_file(&path).is_ok() {
                events.log(&format!("ubisoft: removed {name}, which this install leaves out"));
            }
        }
    }

    std::fs::create_dir_all(&req.folder).map_err(|e| Stop::Failed(format!("Could not create the game folder: {e}")))?;
    let total: u64 = m.files.iter().map(|f| f.size).sum();
    let files_total = m.files.len() as u32;
    let mut pending: Vec<usize> = Vec::new();
    let mut done_bytes = 0u64;
    let mut done_files = 0u32;
    for (i, f) in m.files.iter().enumerate() {
        let path = safe_path(&req.folder, &f.name).map_err(fail)?;
        if f.dir {
            std::fs::create_dir_all(&path).map_err(|e| Stop::Failed(e.to_string()))?;
            done_files += 1;
            continue;
        }
        if std::fs::metadata(&path).map(|md| md.len() == f.size).unwrap_or(false) && already_installed(&path, f) {
            done_bytes += f.size;
            done_files += 1;
            continue;
        }
        pending.push(i);
    }
    let pending_bytes: u64 = pending.iter().map(|&i| m.files[i].size).sum();
    events.sizes(pending_bytes, total);
    events.log(&format!("ubisoft: {} of {} files to fetch, {pending_bytes} bytes", pending.len(), m.files.len()));

    let bucketed = match pending.iter().flat_map(|&i| m.files[i].slices.first()).next() {
        None => true,
        Some(s) => {
            let h = slice_hash(s);
            let probe = sess.sign(req, &[manifest::slice_path(true, &h), manifest::slice_path(false, &h)], events).await.map_err(fail)?;
            let mut v3 = true;
            for (layout, list) in [true, false].into_iter().zip(probe.iter()) {
                if let Some(u) = list.first() {
                    if client.get(u).send().await.map(|r| r.status().is_success()).unwrap_or(false) {
                        v3 = layout;
                        break;
                    }
                }
            }
            v3
        }
    };

    let mut parts: HashMap<usize, Mutex<std::fs::File>> = HashMap::new();
    let mut remaining: HashMap<usize, usize> = HashMap::new();
    let mut jobs = Vec::new();
    for &i in &pending {
        let f: &File = &m.files[i];
        let path = safe_path(&req.folder, &f.name).map_err(fail)?;
        if let Some(parent) = path.parent() {
            std::fs::create_dir_all(parent).map_err(|e| Stop::Failed(e.to_string()))?;
        }
        let part = path.with_extension(format!("{}part", path.extension().map(|e| format!("{}.", e.to_string_lossy())).unwrap_or_default()));
        let file = std::fs::File::create(&part).map_err(|e| Stop::Failed(format!("Could not write {}: {e}", f.name)))?;
        file.set_len(f.size).map_err(|e| Stop::Failed(e.to_string()))?;
        parts.insert(i, Mutex::new(file));
        remaining.insert(i, f.slices.len());
        for (si, s) in f.slices.iter().enumerate() {
            jobs.push(Job { file: i, slice: si, path: manifest::slice_path(bucketed, &slice_hash(s)) });
        }
    }

    let progress = AtomicU64::new(done_bytes);
    let mut finished_files = done_files;
    let mut last_report = Instant::now();
    events.status("Downloading");
    for batch in jobs.chunks(SIGN_BATCH) {
        if cancel.load(Ordering::SeqCst) {
            return Err(Stop::Cancelled);
        }
        let paths: Vec<String> = batch.iter().map(|j| j.path.clone()).collect();
        let signed = sess.sign(req, &paths, events).await.map_err(fail)?;
        let mut slices = stream::iter(batch.iter().zip(signed.into_iter()))
            .map(|(job, urls)| {
                let client = &client;
                let m = &m;
                async move {
                    let s = &m.files[job.file].slices[job.slice];
                    let raw = fetch(client, &urls).await?;
                    let out = checked(m.compression, &raw, s).map_err(|e| format!("{}: {e}", m.files[job.file].name))?;
                    Ok::<_, String>((job.file, job.slice, out))
                }
            })
            .buffer_unordered(WINDOW);
        while let Some(r) = slices.next().await {
            if cancel.load(Ordering::SeqCst) {
                return Err(Stop::Cancelled);
            }
            let (fi, si, data) = r.map_err(fail)?;
            let s = &m.files[fi].slices[si];
            {
                let mut f = parts[&fi].lock().unwrap();
                f.seek(SeekFrom::Start(s.offset)).and_then(|_| f.write_all(&data)).map_err(|e| Stop::Failed(format!("Could not write {}: {e}", m.files[fi].name)))?;
            }
            progress.fetch_add(data.len() as u64, Ordering::Relaxed);
            let left = remaining.get_mut(&fi).unwrap();
            *left -= 1;
            if *left == 0 {
                let f = parts.remove(&fi).unwrap().into_inner().unwrap();
                f.sync_all().map_err(|e| Stop::Failed(e.to_string()))?;
                drop(f);
                let path = safe_path(&req.folder, &m.files[fi].name).map_err(fail)?;
                let part = path.with_extension(format!("{}part", path.extension().map(|e| format!("{}.", e.to_string_lossy())).unwrap_or_default()));
                std::fs::rename(&part, &path).map_err(|e| Stop::Failed(format!("Could not finish {}: {e}", m.files[fi].name)))?;
                finished_files += 1;
            }
            if last_report.elapsed() > Duration::from_millis(250) {
                events.progress(progress.load(Ordering::Relaxed), total, finished_files, files_total);
                last_report = Instant::now();
            }
        }
    }
    for i in pending.iter().copied().filter(|i| m.files[*i].slices.is_empty()) {
        let path = safe_path(&req.folder, &m.files[i].name).map_err(fail)?;
        parts.remove(&i);
        let part = path.with_extension(format!("{}part", path.extension().map(|e| format!("{}.", e.to_string_lossy())).unwrap_or_default()));
        std::fs::rename(&part, &path).map_err(|e| Stop::Failed(e.to_string()))?;
        finished_files += 1;
    }
    events.progress(total, total, finished_files, files_total);
    Ok(Done { files: m.files.len(), bytes: total, chunks: m.chunks.clone(), setup: setup_json(&m) })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn refuses_paths_that_leave_the_folder() {
        let root = Path::new("/games/x");
        assert!(safe_path(root, "../evil").is_err());
        assert!(safe_path(root, "a/../../evil").is_err());
        assert_eq!(safe_path(root, "/bin/game.exe").unwrap(), root.join("bin/game.exe"));
    }

    #[test]
    fn checks_a_slice_as_served_and_unpacked() {
        let data = b"slice".to_vec();
        let ok = manifest::Slice { size: 5, download_size: Some(5), download_sha1: sha1(&data), content_sha1: Vec::new(), offset: 0 };
        assert_eq!(checked(0, &data, &ok).unwrap(), data);
        let bad = manifest::Slice { download_sha1: vec![0; 20], ..ok.clone() };
        assert!(checked(0, &data, &bad).is_err());
        let short = manifest::Slice { download_size: Some(4), ..ok };
        assert!(checked(0, &data, &short).is_err());
    }
}
