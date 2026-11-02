use std::collections::HashMap;
use std::io::Read;

pub const BINARY_MAGIC: u32 = 0x44BE_C00C;
pub const DEFAULT_WINDOW: u32 = 1024 * 1024;
const MIN_HEADER: usize = 41;
const MAX_BODY: usize = 1 << 30;

pub type Guid = [u32; 4];
pub type Sha1 = [u8; 20];

pub fn guid_text(guid: &Guid) -> String {
    format!("{:08X}{:08X}{:08X}{:08X}", guid[0], guid[1], guid[2], guid[3])
}

pub fn guid_from_hex(text: &str) -> Option<Guid> {
    let head = text.get(..32)?;
    if !head.bytes().all(|b| b.is_ascii_hexdigit()) {
        return None;
    }
    let mut out = [0u32; 4];
    for (i, slot) in out.iter_mut().enumerate() {
        *slot = u32::from_str_radix(&head[i * 8..i * 8 + 8], 16).ok()?;
    }
    Some(out)
}

pub fn sha1_hex(hash: &Sha1) -> String {
    hash.iter().map(|b| format!("{b:02x}")).collect()
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct ChunkInfo {
    pub guid: Guid,
    pub rolling_hash: u64,
    pub sha1: Option<Sha1>,
    pub group: u64,
    pub window: u32,
    pub window_exact: bool,
    pub cdn_size: u64,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct PartRef {
    pub guid: Guid,
    pub offset: u32,
    pub size: u32,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct FileEntry {
    pub path: String,
    pub sha1: Option<Sha1>,
    pub tags: Vec<String>,
    pub parts: Vec<PartRef>,
    pub size: u64,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Variant {
    Binary,
    Json,
}

#[derive(Clone, Debug)]
pub struct Manifest {
    pub variant: Variant,
    pub version: i64,
    pub build_version: String,
    pub launch_exe: String,
    pub launch_command: String,
    pub prereq_name: String,
    pub prereq_path: String,
    pub prereq_read: bool,
    pub chunks: Vec<ChunkInfo>,
    pub chunk_index: HashMap<Guid, usize>,
    pub files: Vec<FileEntry>,
}

impl Manifest {
    pub fn chunk(&self, guid: &Guid) -> Option<&ChunkInfo> {
        self.chunk_index.get(guid).map(|&i| &self.chunks[i])
    }

    pub fn chunk_folder(&self) -> &'static str {
        chunk_folder(self.variant, self.version)
    }

    pub fn chunk_path(&self, chunk: &ChunkInfo) -> String {
        format!(
            "{}/{:02}/{:016X}_{}.chunk",
            self.chunk_folder(),
            chunk.group,
            chunk.rolling_hash,
            guid_text(&chunk.guid)
        )
    }
}

pub fn chunk_folder(variant: Variant, version: i64) -> &'static str {
    match version {
        v if v >= 15 => "ChunksV4",
        v if v >= 6 => "ChunksV3",
        v if v >= 3 => "ChunksV2",
        _ => match variant {
            Variant::Binary => "Chunks",
            Variant::Json => "ChunksV4",
        },
    }
}

pub fn parse(bytes: &[u8]) -> Result<Manifest, String> {
    if bytes.len() < 4 {
        return Err("manifest too short".into());
    }
    if u32::from_le_bytes([bytes[0], bytes[1], bytes[2], bytes[3]]) == BINARY_MAGIC {
        parse_binary(bytes)
    } else {
        parse_json(bytes)
    }
}

fn normalise_path(raw: &str) -> String {
    raw.replace('\\', "/")
}

struct Reader<'a> {
    buf: &'a [u8],
    pos: usize,
}

impl<'a> Reader<'a> {
    fn new(buf: &'a [u8]) -> Self {
        Reader { buf, pos: 0 }
    }

    fn take(&mut self, n: usize) -> Result<&'a [u8], String> {
        let end = self.pos.checked_add(n).ok_or("length overflow")?;
        if end > self.buf.len() {
            return Err(format!("read past the end at {} (+{n})", self.pos));
        }
        let out = &self.buf[self.pos..end];
        self.pos = end;
        Ok(out)
    }

    fn u8(&mut self) -> Result<u8, String> {
        Ok(self.take(1)?[0])
    }

    fn u32(&mut self) -> Result<u32, String> {
        let b = self.take(4)?;
        Ok(u32::from_le_bytes([b[0], b[1], b[2], b[3]]))
    }

    fn i32(&mut self) -> Result<i32, String> {
        Ok(self.u32()? as i32)
    }

    fn u64(&mut self) -> Result<u64, String> {
        let b = self.take(8)?;
        let mut a = [0u8; 8];
        a.copy_from_slice(b);
        Ok(u64::from_le_bytes(a))
    }

    fn i64(&mut self) -> Result<i64, String> {
        Ok(self.u64()? as i64)
    }

    fn guid(&mut self) -> Result<Guid, String> {
        Ok([self.u32()?, self.u32()?, self.u32()?, self.u32()?])
    }

    fn sha1(&mut self) -> Result<Sha1, String> {
        let mut out = [0u8; 20];
        out.copy_from_slice(self.take(20)?);
        Ok(out)
    }

    fn count(&mut self, min_item_bytes: usize) -> Result<usize, String> {
        let n = self.i32()?;
        if n < 0 {
            return Err(format!("negative count {n}"));
        }
        let n = n as usize;
        let left = self.buf.len() - self.pos;
        if n.saturating_mul(min_item_bytes.max(1)) > left {
            return Err(format!("count {n} does not fit the section"));
        }
        Ok(n)
    }

    fn fstring(&mut self) -> Result<String, String> {
        let len = self.i32()? as i64;
        if len == 0 {
            return Ok(String::new());
        }
        if len > 0 {
            let raw = self.take(len as usize)?;
            let text = &raw[..raw.len() - 1];
            Ok(text
                .iter()
                .map(|&b| if b < 0x80 { b as char } else { '\u{FFFD}' })
                .collect())
        } else {
            let units = (-len) as usize;
            let raw = self.take(units.checked_mul(2).ok_or("length overflow")?)?;
            let mut code_units: Vec<u16> = raw
                .chunks_exact(2)
                .map(|p| u16::from_le_bytes([p[0], p[1]]))
                .collect();
            code_units.pop();
            Ok(String::from_utf16_lossy(&code_units))
        }
    }
}

fn section<'a>(body: &'a [u8], start: usize) -> Result<(&'a [u8], usize), String> {
    let mut r = Reader::new(&body[start.min(body.len())..]);
    let size = r.i32()?;
    if size < 4 {
        return Err(format!("section at {start} has size {size}"));
    }
    let end = start
        .checked_add(size as usize)
        .filter(|&e| e <= body.len())
        .ok_or_else(|| format!("section at {start} runs past the body"))?;
    Ok((&body[start + 4..end], end))
}

fn parse_binary(bytes: &[u8]) -> Result<Manifest, String> {
    if bytes.len() < MIN_HEADER {
        return Err("binary manifest header too short".into());
    }
    let mut h = Reader::new(bytes);
    h.u32()?;
    let header_size = h.i32()?;
    let raw_size = h.i32()?;
    let _stored_size = h.i32()?;
    let _body_sha1 = h.sha1()?;
    let flags = h.u8()?;
    let version = h.i32()? as i64;
    if header_size < MIN_HEADER as i32 || header_size as usize > bytes.len() {
        return Err(format!("bad header size {header_size}"));
    }
    if raw_size < 0 || raw_size as usize > MAX_BODY {
        return Err(format!("bad body size {raw_size}"));
    }
    if flags & 2 != 0 {
        return Err("encrypted manifests are not supported".into());
    }
    let stored = &bytes[header_size as usize..];
    let inflated;
    let body: &[u8] = if flags & 1 != 0 {
        let want = raw_size as usize;
        let mut out = Vec::with_capacity(want);
        flate2::read::ZlibDecoder::new(stored)
            .take(want as u64 + 1)
            .read_to_end(&mut out)
            .map_err(|e| format!("manifest body does not inflate: {e}"))?;
        if out.len() != want {
            return Err(format!("manifest body inflated to {} bytes, header says {want}", out.len()));
        }
        inflated = out;
        &inflated
    } else {
        stored
    };

    let (meta, after_meta) = section(body, 0)?;
    let meta = read_meta(meta);
    let (chunk_list, after_chunks) = section(body, after_meta)?;
    let (chunks, chunk_index) = read_chunk_list(chunk_list)?;
    let (file_list, _after_files) = section(body, after_chunks)?;
    let files = read_file_list(file_list)?;

    Ok(Manifest {
        variant: Variant::Binary,
        version,
        build_version: meta.build_version,
        launch_exe: meta.launch_exe,
        launch_command: meta.launch_command,
        prereq_name: meta.prereq_name,
        prereq_path: meta.prereq_path,
        prereq_read: meta.complete,
        chunks,
        chunk_index,
        files,
    })
}

#[derive(Default)]
struct Meta {
    build_version: String,
    launch_exe: String,
    launch_command: String,
    prereq_name: String,
    prereq_path: String,
    complete: bool,
}

fn read_meta(data: &[u8]) -> Meta {
    let mut meta = Meta::default();
    let mut r = Reader::new(data);
    let _ = (|| -> Result<(), String> {
        r.u8()?;
        r.u32()?;
        r.u8()?;
        r.u32()?;
        r.fstring()?;
        meta.build_version = r.fstring()?;
        meta.launch_exe = normalise_path(&r.fstring()?);
        meta.launch_command = r.fstring()?;
        let ids = r.count(4)?;
        for _ in 0..ids {
            r.fstring()?;
        }
        meta.prereq_name = r.fstring()?;
        meta.prereq_path = normalise_path(&r.fstring()?);
        r.fstring()?;
        meta.complete = true;
        Ok(())
    })();
    meta
}

fn read_chunk_list(data: &[u8]) -> Result<(Vec<ChunkInfo>, HashMap<Guid, usize>), String> {
    let mut r = Reader::new(data);
    r.u8()?;
    let count = r.count(16 + 8 + 20 + 1 + 4 + 8)?;
    let mut guids = Vec::with_capacity(count);
    for _ in 0..count {
        guids.push(r.guid()?);
    }
    let mut rolling = Vec::with_capacity(count);
    for _ in 0..count {
        rolling.push(r.u64()?);
    }
    let mut hashes = Vec::with_capacity(count);
    for _ in 0..count {
        hashes.push(r.sha1()?);
    }
    let mut groups = Vec::with_capacity(count);
    for _ in 0..count {
        groups.push(r.u8()?);
    }
    let mut windows = Vec::with_capacity(count);
    for _ in 0..count {
        windows.push(r.i32()?);
    }
    let mut sizes = Vec::with_capacity(count);
    for _ in 0..count {
        sizes.push(r.i64()?);
    }
    let mut chunks: Vec<ChunkInfo> = Vec::with_capacity(count);
    let mut index: HashMap<Guid, usize> = HashMap::with_capacity(count);
    for i in 0..count {
        let window = windows[i];
        let info = ChunkInfo {
            guid: guids[i],
            rolling_hash: rolling[i],
            sha1: Some(hashes[i]).filter(|h| h.iter().any(|&b| b != 0)),
            group: groups[i] as u64,
            window: if window > 0 { window as u32 } else { DEFAULT_WINDOW },
            window_exact: window > 0,
            cdn_size: sizes[i].max(0) as u64,
        };
        match index.get(&info.guid) {
            Some(&at) => chunks[at] = info,
            None => {
                index.insert(info.guid, chunks.len());
                chunks.push(info);
            }
        }
    }
    Ok((chunks, index))
}

fn read_file_list(data: &[u8]) -> Result<Vec<FileEntry>, String> {
    let mut r = Reader::new(data);
    r.u8()?;
    let count = r.count(4 + 4 + 20 + 1 + 4 + 4)?;
    let mut names = Vec::with_capacity(count);
    for _ in 0..count {
        names.push(normalise_path(&r.fstring()?));
    }
    for _ in 0..count {
        r.fstring()?;
    }
    let mut hashes = Vec::with_capacity(count);
    for _ in 0..count {
        hashes.push(r.sha1()?);
    }
    for _ in 0..count {
        r.u8()?;
    }
    let mut tags = Vec::with_capacity(count);
    for _ in 0..count {
        let n = r.count(4)?;
        let mut list = Vec::with_capacity(n);
        for _ in 0..n {
            let tag = r.fstring()?;
            if !tag.is_empty() {
                list.push(tag);
            }
        }
        tags.push(list);
    }
    let mut files = Vec::with_capacity(count);
    for (i, (path, tag_list)) in names.into_iter().zip(tags).enumerate() {
        let n = r.count(28)?;
        let mut parts = Vec::with_capacity(n);
        let mut size = 0u64;
        for _ in 0..n {
            let start = r.pos;
            let record = r.i32()?;
            if record < 28 {
                return Err(format!("chunk part record of {record} bytes"));
            }
            let guid = r.guid()?;
            let offset = r.u32()?;
            let len = r.u32()?;
            let end = start + record as usize;
            if end > data.len() {
                return Err("chunk part record runs past the section".into());
            }
            r.pos = end;
            size += len as u64;
            parts.push(PartRef { guid, offset, size: len });
        }
        files.push(FileEntry {
            path,
            sha1: Some(hashes[i]).filter(|h| h.iter().any(|&b| b != 0)),
            tags: tag_list,
            parts,
            size,
        });
    }
    Ok(files)
}

pub fn blob(text: &str) -> Option<u64> {
    if !text.bytes().all(|b| b.is_ascii_digit()) {
        return None;
    }
    let mut value = 0u64;
    for (i, group) in text.as_bytes().chunks(3).take(8).enumerate() {
        let n: u64 = std::str::from_utf8(group).ok()?.parse().ok()?;
        value = value.wrapping_add(n.wrapping_shl(8 * i as u32));
    }
    Some(value)
}

fn blob_of(value: Option<&serde_json::Value>) -> Option<u64> {
    value.and_then(|v| v.as_str()).and_then(blob)
}

fn guid_map(obj: Option<&serde_json::Value>) -> HashMap<Guid, u64> {
    let mut out = HashMap::new();
    if let Some(map) = obj.and_then(|v| v.as_object()) {
        for (k, v) in map {
            if let Some(g) = guid_from_hex(k) {
                out.insert(g, v.as_str().and_then(blob).unwrap_or(0));
            }
        }
    }
    out
}

fn parse_json(bytes: &[u8]) -> Result<Manifest, String> {
    let doc: serde_json::Value =
        serde_json::from_slice(bytes).map_err(|e| format!("manifest is neither binary nor JSON: {e}"))?;
    let str_field = |k: &str| doc.get(k).and_then(|v| v.as_str()).unwrap_or("").to_string();
    let version = blob_of(doc.get("ManifestFileVersion")).unwrap_or(0) as i64;
    let launch = doc
        .get("LaunchExeString")
        .and_then(|v| v.as_str())
        .or_else(|| doc.get("LaunchExe").and_then(|v| v.as_str()))
        .unwrap_or("");

    let hashes = doc
        .get("ChunkHashList")
        .and_then(|v| v.as_object())
        .ok_or("JSON manifest has no ChunkHashList")?;
    let groups = guid_map(doc.get("DataGroupList"));
    let sizes = guid_map(doc.get("ChunkFilesizeList"));
    let mut chunks = Vec::with_capacity(hashes.len());
    let mut chunk_index = HashMap::with_capacity(hashes.len());
    for (key, value) in hashes {
        if key.len() < 32 {
            continue;
        }
        let guid = guid_from_hex(key).ok_or_else(|| format!("chunk key {key} is not hex"))?;
        let info = ChunkInfo {
            guid,
            rolling_hash: value.as_str().and_then(blob).unwrap_or(0),
            sha1: None,
            group: groups.get(&guid).copied().unwrap_or(0),
            window: DEFAULT_WINDOW,
            window_exact: false,
            cdn_size: sizes.get(&guid).copied().unwrap_or(0),
        };
        match chunk_index.get(&guid) {
            Some(&at) => chunks[at] = info,
            None => {
                chunk_index.insert(guid, chunks.len());
                chunks.push(info);
            }
        }
    }

    let list = doc
        .get("FileManifestList")
        .and_then(|v| v.as_array())
        .ok_or("JSON manifest has no FileManifestList")?;
    let mut files = Vec::with_capacity(list.len());
    for entry in list {
        let path = normalise_path(entry.get("Filename").and_then(|v| v.as_str()).unwrap_or(""));
        let tags = entry
            .get("InstallTags")
            .and_then(|v| v.as_array())
            .map(|a| {
                a.iter()
                    .filter_map(|t| t.as_str())
                    .filter(|t| !t.is_empty())
                    .map(str::to_string)
                    .collect()
            })
            .unwrap_or_default();
        let mut parts = Vec::new();
        let mut size = 0u64;
        if let Some(list) = entry.get("FileChunkParts").and_then(|v| v.as_array()) {
            for part in list {
                let guid = part
                    .get("Guid")
                    .and_then(|v| v.as_str())
                    .and_then(guid_from_hex)
                    .unwrap_or([0; 4]);
                let offset = blob_of(part.get("Offset")).unwrap_or(0) as u32;
                let len = blob_of(part.get("Size")).unwrap_or(0) as u32;
                size += len as u64;
                parts.push(PartRef { guid, offset, size: len });
            }
        }
        files.push(FileEntry { path, sha1: None, tags, parts, size });
    }

    Ok(Manifest {
        variant: Variant::Json,
        version,
        build_version: str_field("BuildVersionString"),
        launch_exe: normalise_path(launch),
        launch_command: str_field("LaunchCommand"),
        prereq_name: str_field("PrereqName"),
        prereq_path: normalise_path(&str_field("PrereqPath")),
        prereq_read: true,
        chunks,
        chunk_index,
        files,
    })
}

#[cfg(test)]
pub mod testing {
    use super::*;
    use std::io::Write;

    pub fn fstring(out: &mut Vec<u8>, s: &str) {
        if s.is_empty() {
            out.extend_from_slice(&0i32.to_le_bytes());
        } else if s.is_ascii() {
            out.extend_from_slice(&((s.len() + 1) as i32).to_le_bytes());
            out.extend_from_slice(s.as_bytes());
            out.push(0);
        } else {
            let units: Vec<u16> = s.encode_utf16().collect();
            out.extend_from_slice(&(-((units.len() + 1) as i32)).to_le_bytes());
            for u in units {
                out.extend_from_slice(&u.to_le_bytes());
            }
            out.extend_from_slice(&[0, 0]);
        }
    }

    fn with_size(mut payload: Vec<u8>) -> Vec<u8> {
        let mut out = ((payload.len() + 4) as i32).to_le_bytes().to_vec();
        out.append(&mut payload);
        out
    }

    pub struct TestFile {
        pub path: String,
        pub sha1: Sha1,
        pub tags: Vec<String>,
        pub parts: Vec<PartRef>,
    }

    pub struct TestManifest {
        pub version: i32,
        pub build: String,
        pub exe: String,
        pub prereq: Option<(String, String)>,
        pub chunks: Vec<ChunkInfo>,
        pub files: Vec<TestFile>,
        pub compress: bool,
        pub meta_tail: Vec<u8>,
    }

    pub fn build(m: &TestManifest) -> Vec<u8> {
        let mut meta = vec![0u8];
        meta.extend_from_slice(&18u32.to_le_bytes());
        meta.push(1);
        meta.extend_from_slice(&0u32.to_le_bytes());
        fstring(&mut meta, "App");
        fstring(&mut meta, &m.build);
        fstring(&mut meta, &m.exe);
        fstring(&mut meta, "-PCStore=EGS \"-log=a b.txt\"");
        if let Some((name, path)) = &m.prereq {
            meta.extend_from_slice(&1i32.to_le_bytes());
            fstring(&mut meta, "prereq-id");
            fstring(&mut meta, name);
            fstring(&mut meta, path);
            fstring(&mut meta, "/quiet");
        }
        meta.extend_from_slice(&m.meta_tail);

        let mut cl = vec![2u8];
        cl.extend_from_slice(&(m.chunks.len() as i32).to_le_bytes());
        for c in &m.chunks {
            for v in c.guid {
                cl.extend_from_slice(&v.to_le_bytes());
            }
        }
        for c in &m.chunks {
            cl.extend_from_slice(&c.rolling_hash.to_le_bytes());
        }
        for c in &m.chunks {
            cl.extend_from_slice(&c.sha1.unwrap_or([0; 20]));
        }
        for c in &m.chunks {
            cl.push(c.group as u8);
        }
        for c in &m.chunks {
            cl.extend_from_slice(&(c.window as i32).to_le_bytes());
        }
        for c in &m.chunks {
            cl.extend_from_slice(&(c.cdn_size as i64).to_le_bytes());
        }
        cl.extend_from_slice(&[9, 9, 9]);

        let mut fl = vec![0u8];
        fl.extend_from_slice(&(m.files.len() as i32).to_le_bytes());
        for f in &m.files {
            fstring(&mut fl, &f.path);
        }
        for _ in &m.files {
            fstring(&mut fl, "");
        }
        for f in &m.files {
            fl.extend_from_slice(&f.sha1);
        }
        for _ in &m.files {
            fl.push(0);
        }
        for f in &m.files {
            fl.extend_from_slice(&(f.tags.len() as i32).to_le_bytes());
            for t in &f.tags {
                fstring(&mut fl, t);
            }
        }
        for f in &m.files {
            fl.extend_from_slice(&(f.parts.len() as i32).to_le_bytes());
            for p in &f.parts {
                fl.extend_from_slice(&32i32.to_le_bytes());
                for v in p.guid {
                    fl.extend_from_slice(&v.to_le_bytes());
                }
                fl.extend_from_slice(&p.offset.to_le_bytes());
                fl.extend_from_slice(&p.size.to_le_bytes());
                fl.extend_from_slice(&[0, 0, 0, 0]);
            }
        }

        let mut body = with_size(meta);
        body.extend(with_size(cl));
        body.extend(with_size(fl));
        body.extend(with_size(vec![0, 0, 0, 0, 0]));

        let stored = if m.compress {
            let mut enc = flate2::write::ZlibEncoder::new(Vec::new(), flate2::Compression::default());
            enc.write_all(&body).unwrap();
            enc.finish().unwrap()
        } else {
            body.clone()
        };
        let header_size = 45i32;
        let mut out = Vec::new();
        out.extend_from_slice(&BINARY_MAGIC.to_le_bytes());
        out.extend_from_slice(&header_size.to_le_bytes());
        out.extend_from_slice(&(body.len() as i32).to_le_bytes());
        out.extend_from_slice(&(stored.len() as i32).to_le_bytes());
        out.extend_from_slice(&[0u8; 20]);
        out.push(if m.compress { 1 } else { 0 });
        out.extend_from_slice(&m.version.to_le_bytes());
        out.extend_from_slice(&[7, 7, 7, 7]);
        out.extend_from_slice(&stored);
        out
    }

    pub fn chunk(n: u32, window: u32) -> ChunkInfo {
        ChunkInfo {
            guid: [n, n + 1, 0, 0x8000_0000],
            rolling_hash: 0x0123_4567_89AB_CDEF ^ n as u64,
            sha1: None,
            group: (n % 100) as u64,
            window,
            window_exact: true,
            cdn_size: 1000,
        }
    }
}

#[cfg(test)]
mod tests {
    use super::testing::*;
    use super::*;

    fn sample(compress: bool, prereq: bool) -> TestManifest {
        let c1 = chunk(1, 16);
        let c2 = chunk(2, 8);
        TestManifest {
            version: 21,
            build: "1.2.3-CL-99".into(),
            exe: "Binaries\\Win64\\Game.exe".into(),
            prereq: prereq.then(|| ("UE4 Prerequisites (x64)".into(), "Engine\\Extras\\UE4PrereqSetup_x64.exe".into())),
            chunks: vec![c1.clone(), c2.clone()],
            files: vec![
                TestFile {
                    path: "Game\\Content\\a.pak".into(),
                    sha1: [1; 20],
                    tags: vec![],
                    parts: vec![
                        PartRef { guid: c1.guid, offset: 0, size: 10 },
                        PartRef { guid: c2.guid, offset: 2, size: 6 },
                    ],
                },
                TestFile {
                    path: "Lang/日本語.txt".into(),
                    sha1: [0; 20],
                    tags: vec!["Japanese".into(), "".into()],
                    parts: vec![PartRef { guid: c1.guid, offset: 10, size: 6 }],
                },
                TestFile { path: "empty.txt".into(), sha1: [0; 20], tags: vec![], parts: vec![] },
            ],
            compress,
            meta_tail: vec![1, 2, 3],
        }
    }

    #[test]
    fn binary_manifest_round_trip_compressed_and_stored() {
        for compress in [true, false] {
            let m = parse(&build(&sample(compress, true))).unwrap();
            assert_eq!(m.variant, Variant::Binary);
            assert_eq!(m.version, 21);
            assert_eq!(m.chunk_folder(), "ChunksV4");
            assert_eq!(m.build_version, "1.2.3-CL-99");
            assert_eq!(m.launch_exe, "Binaries/Win64/Game.exe");
            assert_eq!(m.launch_command, "-PCStore=EGS \"-log=a b.txt\"");
            assert_eq!(m.prereq_name, "UE4 Prerequisites (x64)");
            assert_eq!(m.prereq_path, "Engine/Extras/UE4PrereqSetup_x64.exe");
            assert!(m.prereq_read);
            assert_eq!(m.chunks.len(), 2);
            assert_eq!(m.files.len(), 3);
            let a = &m.files[0];
            assert_eq!(a.path, "Game/Content/a.pak");
            assert_eq!(a.size, 16);
            assert_eq!(a.sha1, Some([1; 20]));
            assert_eq!(a.parts[1].offset, 2);
            assert_eq!(m.files[1].path, "Lang/日本語.txt");
            assert_eq!(m.files[1].tags, vec!["Japanese".to_string()]);
            assert_eq!(m.files[1].sha1, None);
            assert_eq!(m.files[2].size, 0);
        }
    }

    #[test]
    fn a_short_meta_section_keeps_the_prereq_flag_unset() {
        let mut s = sample(true, false);
        s.meta_tail.clear();
        let m = parse(&build(&s)).unwrap();
        assert!(!m.prereq_read);
        assert_eq!(m.build_version, "1.2.3-CL-99");
        assert_eq!(m.files.len(), 3);
    }

    #[test]
    fn duplicate_chunks_keep_first_position_and_last_values() {
        let mut s = sample(false, true);
        let mut again = s.chunks[0].clone();
        again.rolling_hash = 42;
        s.chunks.push(again);
        let m = parse(&build(&s)).unwrap();
        assert_eq!(m.chunks.len(), 2);
        assert_eq!(m.chunks[0].rolling_hash, 42);
    }

    #[test]
    fn chunk_paths_and_guid_text() {
        let m = parse(&build(&sample(false, true))).unwrap();
        let c = &m.chunks[0];
        assert_eq!(guid_text(&[0xDEADBEEF, 0xFFFFFFFF, 0, 0x80000000]), "DEADBEEFFFFFFFFF0000000080000000");
        assert_eq!(
            m.chunk_path(c),
            format!("ChunksV4/01/{:016X}_00000001000000020000000080000000.chunk", c.rolling_hash)
        );
    }

    #[test]
    fn truncated_or_inconsistent_bodies_are_rejected() {
        let good = build(&sample(true, true));
        assert!(parse(&good[..good.len() - 10]).is_err());
        let mut lying = good.clone();
        lying[8..12].copy_from_slice(&5i32.to_le_bytes());
        assert!(parse(&lying).is_err());
        assert!(parse(&[0x0C, 0xC0]).is_err());
    }

    #[test]
    fn chunk_folder_per_version_and_variant() {
        assert_eq!(chunk_folder(Variant::Binary, 15), "ChunksV4");
        assert_eq!(chunk_folder(Variant::Binary, 14), "ChunksV3");
        assert_eq!(chunk_folder(Variant::Binary, 6), "ChunksV3");
        assert_eq!(chunk_folder(Variant::Binary, 5), "ChunksV2");
        assert_eq!(chunk_folder(Variant::Binary, 3), "ChunksV2");
        assert_eq!(chunk_folder(Variant::Binary, 2), "Chunks");
        assert_eq!(chunk_folder(Variant::Json, 2), "ChunksV4");
        assert_eq!(chunk_folder(Variant::Json, 0), "ChunksV4");
    }

    #[test]
    fn blob_numbers() {
        assert_eq!(blob("001000000000"), Some(1));
        assert_eq!(blob("000001000000"), Some(256));
        assert_eq!(blob("001002003004005006007008"), Some(0x0807060504030201));
        assert_eq!(blob("255255255255255255255255"), Some(u64::MAX));
        assert_eq!(blob("7"), Some(7));
        assert_eq!(blob(""), Some(0));
        assert_eq!(blob("000000016000"), Some(1_048_576));
        assert_eq!(blob("12a"), None);
    }

    #[test]
    fn json_manifest() {
        let doc = r#"{
            "ManifestFileVersion": "013000000000",
            "LaunchExeString": "Bin\\Game.exe",
            "LaunchCommand": "-PCStore=EGS",
            "BuildVersionString": "++Game+Release-1.0",
            "PrereqName": "Redist", "PrereqPath": "Redist\\setup.exe",
            "ChunkHashList": {
                "0000000A00000000000000000000000F": "001000000000",
                "short": "001",
                "0000000b00000000000000000000000f": "002000000000"
            },
            "DataGroupList": { "0000000A00000000000000000000000F": "005" },
            "ChunkFilesizeList": { "0000000B00000000000000000000000F": "000001000000" },
            "FileManifestList": [
                { "Filename": "Bin\\Game.exe", "InstallTags": ["", "fr"],
                  "FileChunkParts": [
                    { "Guid": "0000000A00000000000000000000000F", "Offset": "000000000000", "Size": "016000000000" },
                    { "Guid": "0000000B00000000000000000000000F", "Offset": "004", "Size": "002" } ] }
            ]
        }"#;
        let m = parse(doc.as_bytes()).unwrap();
        assert_eq!(m.variant, Variant::Json);
        assert_eq!(m.version, 13);
        assert_eq!(m.chunk_folder(), "ChunksV3");
        assert_eq!(m.launch_exe, "Bin/Game.exe");
        assert_eq!(m.launch_command, "-PCStore=EGS");
        assert!(m.prereq_read);
        assert_eq!(m.prereq_path, "Redist/setup.exe");
        assert_eq!(m.chunks.len(), 2);
        assert_eq!(m.chunks[0].group, 5);
        assert_eq!(m.chunks[1].cdn_size, 256);
        assert_eq!(m.chunks[1].rolling_hash, 2);
        assert!(!m.chunks[0].window_exact);
        let f = &m.files[0];
        assert_eq!(f.path, "Bin/Game.exe");
        assert_eq!(f.tags, vec!["fr".to_string()]);
        assert_eq!(f.size, 18);
        assert!(m.chunk(&f.parts[1].guid).is_some());
    }

    #[test]
    fn json_manifest_with_a_bad_chunk_key_is_rejected() {
        let doc = r#"{"ChunkHashList": {"ZZZZZZZZ000000000000000000000000": "001"}, "FileManifestList": []}"#;
        assert!(parse(doc.as_bytes()).is_err());
        assert!(parse(br#"{"FileManifestList": []}"#).is_err());
    }
}
