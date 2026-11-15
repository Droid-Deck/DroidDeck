use std::collections::{HashMap, HashSet};
use std::ffi::CString;
use std::fs::{self, File, OpenOptions};
use std::io::{Read, Seek, SeekFrom, Write};
use std::path::{Component, Path, PathBuf};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Mutex;
use serde_json::Value;
use super::engine::{GogEvents, GogRequest, GogRunResult, http_status_in, is_link_expiry_status};
use super::{md5_hex, md5_hex_file};

static DELTA_LOCK: Mutex<()> = Mutex::new(());
unsafe extern "C" {
    fn dd_gog_delta(source: *const libc::c_char, patch: *const libc::c_char, target: *const libc::c_char,
        cancel: extern "C" fn(*mut libc::c_void) -> libc::c_int, opaque: *mut libc::c_void) -> libc::c_int;
}
extern "C" fn cancelled(opaque: *mut libc::c_void) -> libc::c_int {
    unsafe { (*(opaque as *const AtomicBool)).load(Ordering::Relaxed) as libc::c_int }
}

fn http_client(read_timeout: std::time::Duration) -> Result<reqwest::Client, String> {
    reqwest::Client::builder()
        .connect_timeout(std::time::Duration::from_secs(20))
        .read_timeout(read_timeout)
        .build().map_err(|e| e.to_string())
}

pub fn inside(root: &Path, relative: &str) -> Result<PathBuf, String> {
    if relative.is_empty() || relative.contains(['\\', ':', '\0']) || relative.split('/').any(|p| p.is_empty() || p == "." || p == "..") { return Err("invalid GOG path".into()); }
    let mut out = root.to_path_buf();
    for component in Path::new(relative).components() {
        let Component::Normal(part) = component else { return Err("GOG path leaves installation".into()) };
        out.push(part);
        if fs::symlink_metadata(&out).map(|m| m.file_type().is_symlink()).unwrap_or(false) {
            return Err("GOG path is a symbolic link".into());
        }
    }
    Ok(out)
}

fn text<'a>(v: &'a Value, key: &str) -> &'a str { v.get(key).and_then(Value::as_str).unwrap_or("") }
fn num(v: &Value, key: &str) -> u64 { v.get(key).and_then(Value::as_u64).unwrap_or(0) }
fn array<'a>(v: &'a Value, key: &str) -> &'a [Value] { v.get(key).and_then(Value::as_array).map(Vec::as_slice).unwrap_or(&[]) }
fn valid_md5(s: &str) -> bool { s.len() == 32 && s.bytes().all(|c| c.is_ascii_hexdigit()) }

fn matches(path: &Path, file: &Value) -> bool {
    if !fs::metadata(path).map(|m| m.is_file() && m.len() == num(file, "size")).unwrap_or(false) { return false; }
    let md5 = text(file, "md5");
    if valid_md5(md5) { return md5_hex_file(path).map(|h| h.eq_ignore_ascii_case(md5)).unwrap_or(false); }
    let sha = text(file, "sha256");
    if sha.len() == 64 {
        use sha2::{Digest, Sha256};
        let Ok(mut input) = File::open(path) else { return false; };
        let mut digest = Sha256::new(); let mut buffer = [0u8; 64 * 1024];
        loop { let Ok(n) = input.read(&mut buffer) else { return false; }; if n == 0 { break; } digest.update(&buffer[..n]); }
        return super::to_hex(&digest.finalize()).eq_ignore_ascii_case(sha);
    }
    let chunks = array(file, "chunks");
    if chunks.is_empty() { return num(file, "size") == 0; }
    let Ok(mut input) = File::open(path) else { return false; };
    chunks.iter().all(|chunk| {
        let size = num(chunk, "size");
        valid_md5(text(chunk, "md5")) && hash_range(&mut input, size).as_deref() == Some(text(chunk, "md5"))
    })
}

fn hash_range(input: &mut File, size: u64) -> Option<String> {
    let mut digest = crate::md5_small::Md5::new();
    let mut remaining = size;
    let mut buffer = [0u8; 64 * 1024];
    while remaining > 0 {
        let want = remaining.min(buffer.len() as u64) as usize;
        input.read_exact(&mut buffer[..want]).ok()?;
        digest.update(&buffer[..want]); remaining -= want as u64;
    }
    Some(super::to_hex(&digest.finalize()))
}

#[derive(Clone)]
struct Reuse { path: PathBuf, offset: u64, size: u64 }
type ReuseMap = HashMap<(String, u64), Vec<Reuse>>;

fn reuse_map(root: &Path, files: &[Value]) -> Result<ReuseMap, String> {
    let mut result: ReuseMap = HashMap::new();
    for file in files {
        if text(file, "path").to_lowercase().starts_with(".droiddeck-") { return Err("GOG content overlaps launcher metadata".into()); }
        let path = inside(root, text(file, "path"))?;
        let mut offset: u64 = 0;
        for chunk in array(file, "chunks") {
            let size = num(chunk, "size");
            if valid_md5(text(chunk, "md5")) {
                result.entry((text(chunk, "md5").to_ascii_lowercase(), size)).or_default()
                    .push(Reuse { path: path.clone(), offset, size });
            }
            offset = offset.checked_add(size).ok_or("GOG file size overflow")?;
        }
    }
    Ok(result)
}

fn reusable(chunk: &Value, map: &ReuseMap) -> Option<Reuse> {
    let key = (text(chunk, "md5").to_ascii_lowercase(), num(chunk, "size"));
    map.get(&key)?.iter().find(|old| {
        let Ok(mut file) = File::open(&old.path) else { return false; };
        file.seek(SeekFrom::Start(old.offset)).is_ok() && hash_range(&mut file, old.size).map(|h| h.eq_ignore_ascii_case(&key.0)).unwrap_or(false)
    }).cloned()
}

struct Transfer<'a> {
    runtime: tokio::runtime::Runtime,
    client: reqwest::Client,
    cancel: &'a AtomicBool,
    events: &'a dyn GogEvents,
    wire: u64,
    total: u64,
}

impl Transfer<'_> {
    fn fetch(&mut self, urls: &[String], range: Option<(u64, u64)>, dest: &Path, limit: u64) -> Result<(), String> {
        let mut failure = "GOG download has no URLs".to_string();
        for _ in 0..3 {
            for url in urls {
                if self.cancel.load(Ordering::Relaxed) { return Err("cancelled".into()); }
                let result = self.runtime.block_on(async {
                    let mut request = self.client.get(url).header("User-Agent", "GOG Galaxy");
                    if let Some((start, end)) = range { request = request.header("Range", format!("bytes={start}-{end}")); }
                    let mut response = request.send().await.map_err(|_| "GOG connection failed".to_string())?;
                    let status = response.status().as_u16();
                    if status != if range.is_some() { 206 } else { 200 } { return Err(format!("HTTP {status}")); }
                    let mut output = File::create(dest).map_err(|e| e.to_string())?;
                    let mut size = 0u64;
                    while let Some(bytes) = response.chunk().await.map_err(|_| "GOG connection interrupted".to_string())? {
                        if self.cancel.load(Ordering::Relaxed) { return Err("cancelled".into()); }
                        size += bytes.len() as u64;
                        if limit > 0 && size > limit { return Err("GOG response exceeds expected size".into()); }
                        self.wire += bytes.len() as u64;
                        self.events.on_bytes(self.wire, self.total.max(self.wire));
                        output.write_all(&bytes).map_err(|e| e.to_string())?;
                    }
                    if limit > 0 && size != limit { return Err("GOG download is incomplete".into()); }
                    output.sync_all().map_err(|e| e.to_string())
                });
                match result { Ok(()) => return Ok(()), Err(e) => failure = e }
                if http_status_in(&failure).map(is_link_expiry_status).unwrap_or(false) { return Err(failure); }
            }
        }
        Err(failure)
    }
}

fn urls(links: &Value, product: &str, suffix: &str) -> Vec<String> {
    links.get(product).and_then(Value::as_array).map(|bases| bases.iter().filter_map(Value::as_str).map(|base| {
        if base.contains("{GALAXY_PATH}") { base.replace("{GALAXY_PATH}", suffix) }
        else if let Some((path, query)) = base.split_once('?') { format!("{}{suffix}?{query}", path.trim_end_matches('/').to_string() + "/") }
        else { format!("{}{suffix}", base.trim_end_matches('/').to_string() + "/") }
    }).collect()).unwrap_or_default()
}

fn chunk_path(chunk: &Value) -> Result<String, String> {
    let hash = text(chunk, "compressedMd5");
    if !valid_md5(hash) { return Err("GOG chunk has no valid compressed hash".into()); }
    Ok(super::plan::build_cdn_path(hash))
}

fn download_chunk(transfer: &mut Transfer, links: &Value, product: &str, chunk: &Value, scratch: &Path, output: &mut File) -> Result<(), String> {
    transfer.fetch(&urls(links, product, &chunk_path(chunk)?), None, scratch, num(chunk, "compressedSize"))?;
    if !md5_hex_file(scratch).map(|h| h.eq_ignore_ascii_case(text(chunk, "compressedMd5"))).unwrap_or(false) { return Err("GOG compressed chunk checksum mismatch".into()); }
    let mut raw = File::open(scratch).map_err(|e| e.to_string())?;
    let mut head = [0u8; 1]; raw.read_exact(&mut head).map_err(|e| e.to_string())?;
    raw.seek(SeekFrom::Start(0)).map_err(|e| e.to_string())?;
    let stored = num(chunk, "size") == num(chunk, "compressedSize") && text(chunk, "md5").eq_ignore_ascii_case(text(chunk, "compressedMd5"));
    let input: Box<dyn Read> = if !stored && head[0] == 0x78 { Box::new(flate2::read::ZlibDecoder::new(raw)) } else { Box::new(raw) };
    let mut input = input.take(num(chunk, "size") + 1);
    let mut digest = crate::md5_small::Md5::new();
    let mut size = 0u64;
    let mut buffer = [0u8; 64 * 1024];
    loop {
        let n = input.read(&mut buffer).map_err(|_| "GOG chunk decompression failed")?;
        if n == 0 { break; }
        if transfer.cancel.load(Ordering::Relaxed) { return Err("cancelled".into()); }
        digest.update(&buffer[..n]); size += n as u64;
        output.write_all(&buffer[..n]).map_err(|e| e.to_string())?;
    }
    if size != num(chunk, "size") || !super::to_hex(&digest.finalize()).eq_ignore_ascii_case(text(chunk, "md5")) { return Err("GOG chunk checksum mismatch".into()); }
    Ok(())
}

fn apply_delta(root: &Path, file: &Value, patch: &Value, plan: &Value, stage: &Path, transfer: &mut Transfer) -> Result<(), String> {
    let _guard = DELTA_LOCK.lock().map_err(|_| "GOG delta lock poisoned")?;
    let source = inside(root, &text(patch, "path_source").replace('\\', "/"))?;
    if !md5_hex_file(&source).map(|h| h.eq_ignore_ascii_case(text(patch, "md5_source"))).unwrap_or(false) { return Err("GOG delta source changed".into()); }
    let payload = stage.with_extension("delta");
    let packed = stage.with_extension("packed");
    let mut output = File::create(&payload).map_err(|e| e.to_string())?;
    for chunk in array(patch, "chunks") { download_chunk(transfer, &plan["patchLinks"], text(patch, "product"), chunk, &packed, &mut output)?; }
    drop(output);
    if !md5_hex_file(&payload).map(|h| h.eq_ignore_ascii_case(text(patch, "md5"))).unwrap_or(false) { return Err("GOG delta checksum mismatch".into()); }
    let cpath = |p: &Path| CString::new(p.as_os_str().as_encoded_bytes()).map_err(|_| "invalid path");
    let (s, p, t) = (cpath(&source)?, cpath(&payload)?, cpath(stage)?);
    let code = unsafe { dd_gog_delta(s.as_ptr(), p.as_ptr(), t.as_ptr(), cancelled, transfer.cancel as *const AtomicBool as *mut libc::c_void) };
    let _ = fs::remove_file(payload); let _ = fs::remove_file(packed);
    if code != 0 || !matches(stage, file) || !md5_hex_file(stage).map(|h| h.eq_ignore_ascii_case(text(patch, "md5_target"))).unwrap_or(false) { return Err("GOG delta reconstruction failed".into()); }
    Ok(())
}

pub fn run(req: &GogRequest, cancel: &AtomicBool, events: &dyn GogEvents) -> GogRunResult {
    let mut result = GogRunResult::default();
    let work = || -> Result<(u64, u32), String> {
        let plan: Value = serde_json::from_str(req.depot_manifests.first().ok_or("missing GOG maintenance plan")?).map_err(|_| "invalid GOG maintenance plan")?;
        let root = fs::canonicalize(&req.install_dir).map_err(|e| e.to_string())?;
        let files = array(&plan, "files");
        let mut seen = HashSet::new();
        let mut components = HashMap::new();
        for file in files {
            let name = text(file, "path"); inside(&root, name)?;
            if name.to_lowercase().starts_with(".droiddeck-") || !seen.insert(name.to_lowercase()) { return Err("ambiguous GOG path".into()); }
            let mut parent = root.clone();
            let mut relative = PathBuf::new();
            for part in name.split('/') {
                relative.push(part);
                let key = relative.to_string_lossy().to_lowercase();
                if let Some(previous) = components.insert(key, relative.clone()) {
                    if previous != relative { return Err("ambiguous GOG directory".into()); }
                }
                if let Ok(entries) = fs::read_dir(&parent) {
                    for entry in entries.flatten() {
                        let actual = entry.file_name().to_string_lossy().into_owned();
                        if actual != part && actual.to_lowercase() == part.to_lowercase() { return Err("existing path differs only by case".into()); }
                    }
                }
                parent.push(part);
            }
        }
        let map = reuse_map(&root, array(&plan, "oldFiles"))?;
        let stages = inside(&root, ".droiddeck-gog/stage")?;
        fs::create_dir_all(&stages).map_err(|e| e.to_string())?;
        let mut transfer = Transfer {
            runtime: tokio::runtime::Builder::new_current_thread().enable_all().build().map_err(|e| e.to_string())?,
            client: http_client(std::time::Duration::from_secs(60))?,
            cancel, events, wire: 0, total: num(&plan, "downloadBytes"),
        };
        let mut done = 0u32; let mut reused = 0u64; let mut applied = 0u64;
        for file in files {
            if cancel.load(Ordering::Relaxed) { return Err("cancelled".into()); }
            let path = inside(&root, text(file, "path"))?;
            if file.get("directory").and_then(Value::as_bool).unwrap_or(false) {
                fs::create_dir_all(&path).map_err(|e| e.to_string())?; done += 1; continue;
            }
            if matches(&path, file) { done += 1; events.on_file_done(text(file, "path"), num(file, "size"), true, done, files.len() as u32, transfer.wire, transfer.total); continue; }
            let stage = stages.join(md5_hex(text(file, "path").as_bytes()));
            if !matches(&stage, file) {
                let mut start = 0;
                let missing_bytes: u64 = array(file, "chunks").iter().map(|chunk| {
                    let valid = File::open(&stage).ok().map(|mut input| input.seek(SeekFrom::Start(start)).is_ok() &&
                        hash_range(&mut input, num(chunk, "size")).map(|h| h.eq_ignore_ascii_case(text(chunk, "md5"))).unwrap_or(false)).unwrap_or(false);
                    start += num(chunk, "size");
                    if valid || reusable(chunk, &map).is_some() { 0 } else { num(chunk, "compressedSize") }
                }).sum();
                let patch = array(&plan, "patches").iter().find(|p| text(p, "path_target").replace('\\', "/") == text(file, "path") &&
                    array(p, "chunks").iter().map(|c| num(c, "compressedSize")).sum::<u64>() < missing_bytes);
                let delta_ok = if let Some(patch) = patch {
                    match apply_delta(&root, file, patch, &plan, &stage, &mut transfer) {
                        Ok(()) => true,
                        Err(e) => { if cancel.load(Ordering::Relaxed) || http_status_in(&e).map(is_link_expiry_status).unwrap_or(false) { return Err(e); } events.on_log(&format!("gog: delta fallback file={} reason={e}", text(file, "path"))); false }
                    }
                } else { false };
                if !delta_ok {
                    let chunks = array(file, "chunks");
                    if chunks.is_empty() && num(file, "size") > 0 {
                        let offset = num(file, "offset");
                        let remote = text(file, "remotePath");
                        let endpoints = if remote.starts_with("https://") { vec![remote.to_string()] } else { urls(&plan["links"], text(file, "product"), remote.trim_start_matches('/')) };
                        transfer.fetch(&endpoints, Some((offset, offset + num(file, "size") - 1)), &stage, num(file, "size"))?;
                    } else {
                        let mut output = OpenOptions::new().create(true).truncate(false).read(true).write(true).open(&stage).map_err(|e| e.to_string())?;
                        let mut offset = 0u64;
                        for chunk in chunks {
                            if cancel.load(Ordering::Relaxed) { return Err("cancelled".into()); }
                            output.seek(SeekFrom::Start(offset)).map_err(|e| e.to_string())?;
                            let valid_stage = hash_range(&mut output, num(chunk, "size")).map(|h| h.eq_ignore_ascii_case(text(chunk, "md5"))).unwrap_or(false);
                            output.seek(SeekFrom::Start(offset)).map_err(|e| e.to_string())?;
                            if valid_stage { reused += num(chunk, "size"); }
                            else if let Some(old) = reusable(chunk, &map) {
                                let mut source = File::open(old.path).map_err(|e| e.to_string())?;
                                source.seek(SeekFrom::Start(old.offset)).map_err(|e| e.to_string())?;
                                std::io::copy(&mut source.take(old.size), &mut output).map_err(|e| e.to_string())?;
                                reused += old.size;
                            } else {
                                download_chunk(&mut transfer, &plan["links"], text(file, "product"), chunk, &stage.with_extension("packed"), &mut output)?;
                            }
                            offset += num(chunk, "size");
                        }
                        output.set_len(num(file, "size")).map_err(|e| e.to_string())?;
                        output.sync_all().map_err(|e| e.to_string())?;
                    }
                }
            }
            let unhashed_range = array(file, "chunks").is_empty() && !valid_md5(text(file, "md5")) && text(file, "sha256").is_empty();
            if !matches(&stage, file) && !(unhashed_range && fs::metadata(&stage).map(|m| m.len() == num(file, "size")).unwrap_or(false)) { return Err(format!("GOG target verification failed: {}", text(file, "path"))); }
            if cancel.load(Ordering::Relaxed) { return Err("cancelled".into()); }
            inside(&root, text(file, "path"))?;
            fs::create_dir_all(path.parent().ok_or("GOG file has no parent")?).map_err(|e| e.to_string())?;
            File::open(&stage).and_then(|f| f.sync_all()).map_err(|e| e.to_string())?;
            fs::rename(&stage, &path).map_err(|e| e.to_string())?;
            applied += num(file, "size"); done += 1;
            events.on_file_done(text(file, "path"), num(file, "size"), false, done, files.len() as u32, transfer.wire, transfer.total);
            events.on_maintenance(transfer.wire, reused, applied);
            events.on_log(&format!("gog: maintenance wire={} reused={reused} applied={applied} files={done}", transfer.wire));
        }
        for old in array(&plan, "oldFiles") {
            if cancel.load(Ordering::Relaxed) { return Err("cancelled".into()); }
            if !seen.contains(&text(old, "path").to_lowercase()) {
                let path = inside(&root, text(old, "path"))?;
                if matches(&path, old) { fs::remove_file(path).map_err(|e| e.to_string())?; }
            }
        }
        events.on_log(&format!("gog: maintenance complete wire={} reused={reused} applied={applied}", transfer.wire));
        Ok((transfer.wire, done))
    };
    match work() {
        Ok((bytes, files)) => { result.success = true; result.bytes_written = bytes; result.files_done = files; }
        Err(error) => { result.cancelled = cancel.load(Ordering::Relaxed); result.link_expiry = http_status_in(&error).map(is_link_expiry_status).unwrap_or(false); result.error = error; }
    }
    result
}

#[cfg(test)]
mod tests {
    use super::*;
    use flate2::{write::ZlibEncoder, Compression};
    use serde_json::json;
    use std::net::TcpListener;
    use std::sync::{Arc, atomic::AtomicU64};

    struct Events;
    impl GogEvents for Events {
        fn on_file_done(&self, _: &str, _: u64, _: bool, _: u32, _: u32, _: u64, _: u64) {}
        fn on_log(&self, _: &str) {}
    }
    struct Temp(PathBuf);
    impl Temp {
        fn new() -> Self {
            let path = std::env::temp_dir().join(format!("gog-maintenance-{}-{}", std::process::id(), std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).unwrap().as_nanos()));
            fs::create_dir_all(&path).unwrap(); Self(path)
        }
    }
    impl Drop for Temp { fn drop(&mut self) { let _ = fs::remove_dir_all(&self.0); } }

    struct Server { base: String, bytes: Arc<AtomicU64>, stop: Arc<AtomicBool>, thread: Option<std::thread::JoinHandle<()>> }
    impl Server {
        fn new(payloads: HashMap<String, Vec<u8>>, range: bool) -> Self {
            let listener = TcpListener::bind("127.0.0.1:0").unwrap();
            let base = format!("http://{}", listener.local_addr().unwrap());
            listener.set_nonblocking(true).unwrap();
            let stop = Arc::new(AtomicBool::new(false)); let bytes = Arc::new(AtomicU64::new(0));
            let (stopped, count) = (stop.clone(), bytes.clone());
            let thread = std::thread::spawn(move || {
                while !stopped.load(Ordering::Relaxed) {
                    if let Ok((mut stream, _)) = listener.accept() {
                        stream.set_read_timeout(Some(std::time::Duration::from_secs(2))).unwrap();
                        let mut request = Vec::new(); let mut one = [0u8];
                        while stream.read(&mut one).unwrap_or(0) > 0 { request.push(one[0]); if request.ends_with(b"\r\n\r\n") { break; } }
                        let request = String::from_utf8_lossy(&request);
                        let path = request.split_whitespace().nth(1).unwrap_or("");
                        if let Some(body) = payloads.get(path) {
                            let status = if range { "206 Partial Content" } else { "200 OK" };
                            let _ = write!(stream, "HTTP/1.1 {status}\r\nContent-Length: {}\r\nConnection: close\r\n\r\n", body.len());
                            if stream.write_all(body).is_ok() { count.fetch_add(body.len() as u64, Ordering::Relaxed); }
                        } else { let _ = stream.write_all(b"HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"); }
                    } else { std::thread::sleep(std::time::Duration::from_millis(2)); }
                }
            });
            Self { base, bytes, stop, thread: Some(thread) }
        }
    }
    impl Drop for Server { fn drop(&mut self) { self.stop.store(true, Ordering::Relaxed); if let Some(thread) = self.thread.take() { thread.join().unwrap(); } } }

    #[test]
    fn continuing_download_may_exceed_the_read_timeout_in_total() {
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let url = format!("http://{}", listener.local_addr().unwrap());
        let server = std::thread::spawn(move || {
            let (mut stream, _) = listener.accept().unwrap();
            stream.set_read_timeout(Some(std::time::Duration::from_secs(3))).unwrap();
            let mut request = Vec::new(); let mut byte = [0u8];
            while stream.read(&mut byte).unwrap() > 0 { request.push(byte[0]); if request.ends_with(b"\r\n\r\n") { break; } }
            stream.write_all(b"HTTP/1.1 200 OK\r\nContent-Length: 4\r\nConnection: close\r\n\r\n").unwrap();
            for byte in b"abcd" {
                stream.write_all(&[*byte]).unwrap(); stream.flush().unwrap();
                std::thread::sleep(std::time::Duration::from_millis(400));
            }
        });
        let runtime = tokio::runtime::Builder::new_current_thread().enable_all().build().unwrap();
        let client = http_client(std::time::Duration::from_secs(1)).unwrap();
        let body = runtime.block_on(async { client.get(url).send().await.unwrap().bytes().await.unwrap() });
        assert_eq!(&body[..], b"abcd");
        server.join().unwrap();
    }

    fn chunk(data: &[u8]) -> (Value, String, Vec<u8>) {
        let mut encoder = ZlibEncoder::new(Vec::new(), Compression::default()); encoder.write_all(data).unwrap();
        let packed = encoder.finish().unwrap(); let hash = md5_hex(&packed);
        (json!({"md5": md5_hex(data), "size": data.len(), "compressedMd5": hash, "compressedSize": packed.len()}),
            format!("/{}", super::super::plan::build_cdn_path(&hash)), packed)
    }
    fn execute(root: &Path, files: Value, old: Value, server: &Server) -> GogRunResult {
        let plan = json!({"files": files, "oldFiles": old, "patches": [], "links": {"1": [server.base]}, "downloadBytes": 4096});
        run(&GogRequest { install_dir: root.to_string_lossy().into_owned(), depot_manifests: vec![plan.to_string()], ..Default::default() }, &AtomicBool::new(false), &Events)
    }

    #[test]
    fn reuses_moved_and_repeated_chunks_and_downloads_only_missing_bytes() {
        let temp = Temp::new();
        let (a, _, _) = chunk(b"aaaa"); let (b, path, packed) = chunk(b"bbbb");
        fs::write(temp.0.join("archive"), b"aaaaaaaa").unwrap();
        let server = Server::new(HashMap::from([(path, packed.clone())]), false);
        let old = json!([{"path":"archive", "size":8, "md5":md5_hex(b"aaaaaaaa"), "chunks":[a,a]}]);
        let files = json!([{"path":"archive", "size":12, "md5":md5_hex(b"bbbbaaaaaaaa"), "chunks":[b,a,a], "product":"1"}]);
        let result = execute(&temp.0, files, old, &server);
        assert!(result.success, "{}", result.error);
        assert_eq!(fs::read(temp.0.join("archive")).unwrap(), b"bbbbaaaaaaaa");
        assert_eq!(server.bytes.load(Ordering::Relaxed), packed.len() as u64);
        assert_eq!(result.bytes_written, packed.len() as u64);
    }

    #[test]
    fn revalidates_partial_staging_and_preserves_original_until_verified() {
        let temp = Temp::new(); let (a, _, _) = chunk(b"aaaa"); let (b, path, packed) = chunk(b"bbbb");
        let stages = temp.0.join(".droiddeck-gog/stage"); fs::create_dir_all(&stages).unwrap();
        fs::write(temp.0.join("archive"), b"original").unwrap();
        fs::write(stages.join(md5_hex(b"archive")), b"aaaabadd").unwrap();
        let server = Server::new(HashMap::from([(path, packed.clone())]), false);
        let files = json!([{"path":"archive", "size":8, "md5":md5_hex(b"aaaabbbb"), "chunks":[a,b], "product":"1"}]);
        let result = execute(&temp.0, files, json!([]), &server);
        assert!(result.success, "{}", result.error);
        assert_eq!(server.bytes.load(Ordering::Relaxed), packed.len() as u64);
        assert_eq!(fs::read(temp.0.join("archive")).unwrap(), b"aaaabbbb");
    }

    #[test]
    fn unchanged_files_need_no_network_and_obsolete_modified_files_survive() {
        let temp = Temp::new(); let (a, _, _) = chunk(b"aaaa");
        fs::write(temp.0.join("current"), b"aaaa").unwrap();
        fs::write(temp.0.join("obsolete"), b"old").unwrap(); fs::write(temp.0.join("modded"), b"mod").unwrap();
        let server = Server::new(HashMap::new(), false);
        let files = json!([{"path":"current", "size":4, "md5":md5_hex(b"aaaa"), "chunks":[a], "product":"1"}, {"path":"empty", "size":0, "chunks":[], "product":"1"}]);
        let old = json!([{"path":"obsolete", "size":3, "md5":md5_hex(b"old")}, {"path":"modded", "size":3, "md5":md5_hex(b"old")}]);
        let result = execute(&temp.0, files, old, &server);
        assert!(result.success, "{}", result.error);
        assert_eq!(server.bytes.load(Ordering::Relaxed), 0);
        assert!(!temp.0.join("obsolete").exists()); assert!(temp.0.join("modded").exists());
        assert_eq!(fs::metadata(temp.0.join("empty")).unwrap().len(), 0);
    }

    #[test]
    fn gen1_same_size_corruption_is_replaced_using_hash_and_range() {
        let temp = Temp::new(); fs::write(temp.0.join("game"), b"bad!").unwrap();
        let server = Server::new(HashMap::from([("/range".to_string(), b"good".to_vec())]), true);
        let files = json!([{"path":"game", "size":4, "md5":md5_hex(b"good"), "chunks":[], "product":"1", "remotePath":"range", "offset":10}]);
        let result = execute(&temp.0, files, json!([]), &server);
        assert!(result.success, "{}", result.error); assert_eq!(fs::read(temp.0.join("game")).unwrap(), b"good");
        assert_eq!(server.bytes.load(Ordering::Relaxed), 4);
    }

    #[test]
    fn bad_chunks_never_replace_original_and_expired_links_are_reported() {
        let temp = Temp::new(); fs::write(temp.0.join("game"), b"old!").unwrap();
        let (a, path, mut packed) = chunk(b"new!"); packed[0] ^= 1;
        let server = Server::new(HashMap::from([(path, packed)]), false);
        let files = json!([{"path":"game", "size":4, "md5":md5_hex(b"new!"), "chunks":[a], "product":"1"}]);
        let result = execute(&temp.0, files.clone(), json!([]), &server);
        assert!(!result.success); assert_eq!(fs::read(temp.0.join("game")).unwrap(), b"old!");
        let missing = Server::new(HashMap::new(), false);
        let result = execute(&temp.0, files, json!([]), &missing);
        assert!(result.link_expiry, "{}", result.error);
    }

    #[test]
    fn rejects_traversal_symlinks_and_case_collisions_before_fetching() {
        let temp = Temp::new(); let outside = Temp::new();
        std::os::unix::fs::symlink(&outside.0, temp.0.join("linked")).unwrap();
        for path in ["../escape", "/absolute", "a/./b", "a//b", "linked/game"] { assert!(inside(&temp.0, path).is_err(), "{path}"); }
        let server = Server::new(HashMap::new(), false);
        let result = execute(&temp.0, json!([{"path":"Game"}, {"path":"game"}]), json!([]), &server);
        assert!(!result.success); assert_eq!(server.bytes.load(Ordering::Relaxed), 0);
    }

    #[test]
    fn valid_xdelta_downloads_only_delta_and_corrupt_source_falls_back() {
        let old = b"the old archive contents\n".repeat(4096);
        let mut new = b"the new archive contents\n".to_vec(); new.extend_from_slice(&old); new.extend_from_slice(b"END\n");
        let delta = include_bytes!("fixtures/update.vcdiff");
        let (patch_chunk, patch_url, packed_delta) = chunk(delta);
        let (target_chunk, target_url, packed_target) = chunk(&new);
        for mode in 0..3 {
            let corrupt = mode == 1;
            let temp = Temp::new();
            fs::write(temp.0.join("game"), if corrupt { vec![b'x'; old.len()] } else { old.clone() }).unwrap();
            let (delta_chunk, delta_url, delta_body) = if mode == 2 { chunk(b"unsupported VCDIFF") } else { (patch_chunk.clone(), patch_url.clone(), packed_delta.clone()) };
            let server = Server::new(HashMap::from([(delta_url, delta_body.clone()), (target_url.clone(), packed_target.clone())]), false);
            let plan = json!({"files":[{"path":"game", "product":"1", "size":new.len(), "md5":md5_hex(&new), "chunks":[target_chunk]}],
                "oldFiles":[{"path":"game", "size":old.len(), "md5":md5_hex(&old), "chunks":[]}],
                "patches":[{"path_source":"game", "path_target":"game", "product":"1", "md5_source":md5_hex(&old), "md5_target":md5_hex(&new), "md5":if mode == 2 { md5_hex(b"unsupported VCDIFF") } else { md5_hex(delta) }, "chunks":[delta_chunk]}],
                "links":{"1":[server.base]}, "patchLinks":{"1":[server.base]}, "downloadBytes":packed_target.len()});
            let result = run(&GogRequest {install_dir:temp.0.to_string_lossy().into_owned(), depot_manifests:vec![plan.to_string()], ..Default::default()}, &AtomicBool::new(false), &Events);
            assert!(result.success, "{}", result.error);
            assert_eq!(fs::read(temp.0.join("game")).unwrap(), new);
            assert_eq!(server.bytes.load(Ordering::Relaxed), match mode { 1 => packed_target.len(), 2 => delta_body.len() + packed_target.len(), _ => packed_delta.len() } as u64);
        }
    }

    #[test]
    fn stored_chunks_starting_with_x_are_not_mistaken_for_zlib() {
        let temp = Temp::new(); let raw = b"xyz stored data";
        let hash = md5_hex(raw);
        let path = format!("/{}", super::super::plan::build_cdn_path(&hash));
        let server = Server::new(HashMap::from([(path, raw.to_vec())]), false);
        let result = execute(&temp.0, json!([{"path":"game", "product":"1", "size":raw.len(), "md5":hash,
            "chunks":[{"md5":hash,"compressedMd5":hash,"size":raw.len(),"compressedSize":raw.len()}]}]), json!([]), &server);
        assert!(result.success, "{}", result.error);
        assert_eq!(fs::read(temp.0.join("game")).unwrap(), raw);
    }
}
