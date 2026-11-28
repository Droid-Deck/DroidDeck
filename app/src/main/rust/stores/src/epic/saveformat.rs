//! Reader, writer and packer for EGL's own binary save manifests (format version 17) so
//! uploads look like they were made by the official launcher. A manifest is a 41-byte
//! header followed by four length-prefixed sections (meta, chunk list, file list, custom
//! fields). `read` is strict: it consumes every section completely and keeps all payload
//! fields the format can carry. `write` always emits an uncompressed body (`flags = 0`,
//! what EGL itself uploads); reading also accepts compressed manifests, so a compressed
//! manifest survives a round trip through the model but comes back normalised —
//! `write(read(bytes)) == bytes` holds exactly when the manifest is uncompressed. The
//! byte layout mirrors the reference implementation `verify_format.py` in the separate
//! capture workspace `droiddeck/epic-cloud-backup/_tools/` (not part of this repository),
//! which was verified byte for byte against real EGL captures.

use std::io::Read;

use sha1::{Digest, Sha1 as Sha1Hasher};

use super::chunk;
use super::manifest::{self, ChunkInfo, FileEntry, Guid, PartRef, Reader, Sha1};

/// The only save-manifest format this module reads or writes.
pub const FORMAT_VERSION: u32 = 17;
const HEADER_SIZE: usize = 41;
const MAX_BODY: usize = 1 << 30;

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Meta {
    pub data_version: u8,
    pub feature_level: u32,
    pub is_file_data: u8,
    pub app_id: u32,
    pub app_name: String,
    pub build_version: String,
    pub launch_exe: String,
    pub launch_command: String,
    /// Prerequisite ids declared before the four known strings' tail.
    pub prereq_ids: Vec<String>,
    /// Whatever strings follow the prereq ids; real EGL writes three empty ones.
    pub tail: Vec<String>,
}

/// Custom-fields section: the key list first, then the values in the same order.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct CustomFields {
    pub version: u8,
    pub keys: Vec<String>,
    pub values: Vec<String>,
}

/// A version-17 binary save manifest with every payload field kept, so writing back an
/// uncompressed original reproduces its bytes (compressed originals come back normalised
/// to `flags = 0`, which is the shape EGL itself uploads).
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct SaveManifest {
    pub version: u32,
    pub meta: Meta,
    pub chunks: Vec<ChunkInfo>,
    pub files: Vec<FileEntry>,
    /// Symbolic-link target per file, parallel to `files` (empty for regular files).
    pub links: Vec<String>,
    /// Per-file flags byte, parallel to `files`.
    pub file_flags: Vec<u8>,
    pub custom: CustomFields,
}

/// Manifest names must be clean relative paths before anything is written under the
/// mirror or packed into chunks: no absolute path, no backslashes (the format separates
/// with `/`), no empty or `.`/`..` segments.
pub fn safe_save_path(path: &str) -> bool {
    !path.is_empty()
        && !path.starts_with('/')
        && !path.contains('\\')
        && path.split('/').all(|seg| !seg.is_empty() && seg != "." && seg != "..")
}

/// Cloud key of a chunk kept by the save machinery: `ChunksV4/<two-digit group>/<rolling
/// hash>_<guid>.chunk`. The save counterpart of `Manifest::chunk_path`.
pub fn chunk_cloud_path(info: &ChunkInfo) -> String {
    format!(
        "ChunksV4/{:02}/{:016X}_{}.chunk",
        info.group,
        info.rolling_hash,
        manifest::guid_text(&info.guid)
    )
}

/// Cloud key of the save snapshot itself: `manifests/<build version>.manifest`. The build
/// version doubles as a UTC timestamp (`YYYY.MM.DD-HH.MM.SS`) that upload time generates,
/// so callers must hand in one in exactly that shape.
pub fn manifest_cloud_path(build_version: &str) -> String {
    format!("manifests/{build_version}.manifest")
}

/// The screening every packed save name must pass: a clean relative path, ASCII only.
/// EGL's narrow fstr slots are defined as ASCII (wide UTF-16 strings would be the
/// alternative, untested against real EGL), so a non-ASCII name would be published into
/// a manifest the launcher may not read back — refuse instead of guessing. Public so
/// publishers can screen all names up front, before reading any file.
pub fn validate_save_name(path: &str) -> Result<(), String> {
    if !safe_save_path(path) {
        return Err(format!("unsafe save path: {path}"));
    }
    if !path.is_ascii() {
        return Err(format!("save path {path} is not ASCII"));
    }
    Ok(())
}

/// Packs save files the way EGL does, one file at a time: names must arrive in EGL's order
/// (sorted case-insensitively, stable; every one ASCII, where that sort matches EGL's
/// casefold exactly — non-ASCII orders were never verified against it), contents are joined
/// into one stream and cut into 1 MiB blocks, the last one zero-padded. A file crossing a
/// block boundary becomes several parts; an empty file gets none. Every finished block is
/// handed to the caller's callback at once — with its index, in block order — so only one
/// file and at most one block ever sit in memory; the GUID the callback returns (a reused
/// chunk's or a fresh one) lands in every part referencing that block.
pub struct BlockPacker {
    /// The not-yet-full current block; reused across blocks to avoid reallocations.
    prefix: Vec<u8>,
    /// Every part so far, in stream order; a file's parts are a slice of this.
    parts: Vec<PartRef>,
    /// Index into `parts` where the current (not yet sealed) block begins.
    block_start: usize,
    /// How many blocks have been sealed so far.
    blocks: usize,
    /// (path, sha1, size, part start, part count) per fed file, sealed into `FileEntry`s
    /// on `finish` once every part carries its block's GUID.
    pending: Vec<(String, Sha1, u64, usize, usize)>,
}

impl Default for BlockPacker {
    fn default() -> Self {
        Self::new()
    }
}

impl BlockPacker {
    pub fn new() -> Self {
        BlockPacker {
            prefix: Vec::with_capacity(chunk::CHUNK_WINDOW),
            parts: Vec::new(),
            block_start: 0,
            blocks: 0,
            pending: Vec::new(),
        }
    }

    /// Feeds one whole file and returns its SHA-1 (what `FileEntry.sha1` and the answer's
    /// `files[].sha1` carry). Empty files are legal and produce no parts.
    pub fn push(
        &mut self,
        path: &str,
        content: &[u8],
        on_block: &mut dyn FnMut(usize, &[u8]) -> Result<Guid, String>,
    ) -> Result<Sha1, String> {
        validate_save_name(path)?;
        let start = self.parts.len();
        let mut pos = 0usize;
        while pos < content.len() {
            let offset = self.prefix.len() as u32;
            let take = (content.len() - pos).min(chunk::CHUNK_WINDOW - offset as usize) as u32;
            self.parts.push(PartRef { guid: [0; 4], offset, size: take });
            self.prefix.extend_from_slice(&content[pos..pos + take as usize]);
            pos += take as usize;
            if self.prefix.len() == chunk::CHUNK_WINDOW {
                let guid = on_block(self.blocks, &self.prefix)?;
                self.stamp_block(guid);
            }
        }
        let sha: Sha1 = Sha1Hasher::digest(content).into();
        self.pending
            .push((path.to_string(), sha, content.len() as u64, start, self.parts.len() - start));
        Ok(sha)
    }

    /// Seals the final (short) block, zero-padded like EGL does, and returns the file
    /// entries in feed order.
    pub fn finish(
        mut self,
        on_block: &mut dyn FnMut(usize, &[u8]) -> Result<Guid, String>,
    ) -> Result<Vec<FileEntry>, String> {
        if !self.prefix.is_empty() {
            let mut block = std::mem::take(&mut self.prefix);
            block.resize(chunk::CHUNK_WINDOW, 0);
            let guid = on_block(self.blocks, &block)?;
            self.stamp_block(guid);
        }
        Ok(self
            .pending
            .into_iter()
            .map(|(path, sha, size, start, len)| FileEntry {
                path,
                sha1: Some(sha),
                tags: Vec::new(),
                parts: self.parts[start..start + len].to_vec(),
                size,
            })
            .collect())
    }

    /// Stamps a sealed block's GUID into every part of the block and resets the window.
    /// Split from handing the block to the caller so the caller can read `self.prefix`
    /// (or, on `finish`, its moved-out replacement) without fighting `&mut self`.
    fn stamp_block(&mut self, guid: Guid) {
        for part in &mut self.parts[self.block_start..] {
            part.guid = guid;
        }
        self.block_start = self.parts.len();
        self.prefix.clear();
        self.blocks += 1;
    }
}

/// Parses a version-17 binary save manifest. Refuses anything the writer could not
/// faithfully reproduce: other versions or header sizes, encrypted headers, truncated
/// sections, leftover bytes, wide (UTF-16) strings, unsafe file paths. Compressed bodies
/// (flags bit 0) are accepted; `write` normalises them to uncompressed, so only an
/// uncompressed manifest round-trips byte for byte.
pub fn read(bytes: &[u8]) -> Result<SaveManifest, String> {
    if bytes.len() < HEADER_SIZE {
        return Err(format!("manifest header of {} bytes", bytes.len()));
    }
    let mut h = Reader::new(bytes);
    let magic = h.u32()?;
    if magic != manifest::BINARY_MAGIC {
        return Err("not a binary save manifest".into());
    }
    let header_size = h.i32()?;
    let raw_size = h.i32()?;
    let stored_size = h.i32()?;
    let body_sha1 = h.sha1()?;
    let flags = h.u8()?;
    let version = h.u32()?;
    if header_size != HEADER_SIZE as i32 {
        return Err(format!("unexpected manifest header size {header_size}"));
    }
    if raw_size < 0 || raw_size as usize > MAX_BODY {
        return Err(format!("bad manifest body size {raw_size}"));
    }
    if stored_size < 0 || stored_size as usize != bytes.len() - HEADER_SIZE {
        return Err(format!(
            "manifest stored size {stored_size} does not match {} trailing bytes",
            bytes.len() - HEADER_SIZE
        ));
    }
    if flags & 2 != 0 {
        return Err("encrypted save manifests are not supported".into());
    }
    // Unknown flag bits could carry layout changes this writer knows nothing about;
    // parsing on would silently misread the manifest.
    if flags & !3 != 0 {
        return Err(format!("unsupported save manifest flags {flags}"));
    }
    if version != FORMAT_VERSION {
        return Err(format!("unsupported save manifest version {version}"));
    }
    let stored = &bytes[HEADER_SIZE..];
    let body = if flags & 1 != 0 {
        inflate_body(stored, raw_size as usize)?
    } else {
        stored.to_vec()
    };
    if body.len() != raw_size as usize {
        return Err(format!("manifest body of {} bytes, header says {raw_size}", body.len()));
    }
    let got: Sha1 = Sha1Hasher::digest(&body).into();
    if got != body_sha1 {
        return Err("save manifest body does not match its SHA-1".into());
    }

    let mut r = Reader::new(&body);
    let meta = read_meta(r.section()?)?;
    let chunks = read_chunk_list(r.section()?)?;
    let (files, links, file_flags) = read_file_list(r.section()?)?;
    for file in &files {
        if !safe_save_path(&file.path) {
            return Err(format!("manifest lists an unsafe save path: {}", file.path));
        }
    }
    let custom = read_custom(r.section()?)?;
    if r.pos != body.len() {
        return Err(format!("manifest body has {} trailing bytes", body.len() - r.pos));
    }
    Ok(SaveManifest { version, meta, chunks, files, links, file_flags, custom })
}

/// Serialises a save manifest exactly the way EGL lays it out, always with an uncompressed
/// body (`flags = 0`), which is what EGL itself uploads. Reading accepts compressed
/// manifests, but writing normalises them: `read(write(read(x))) == read(x)` holds for
/// every readable manifest, and `write(read(x)) == x` holds whenever `x` was uncompressed.
pub fn write(m: &SaveManifest) -> Vec<u8> {
    let mut meta = Vec::new();
    meta.push(m.meta.data_version);
    meta.extend_from_slice(&m.meta.feature_level.to_le_bytes());
    meta.push(m.meta.is_file_data);
    meta.extend_from_slice(&m.meta.app_id.to_le_bytes());
    fstr(&mut meta, &m.meta.app_name);
    fstr(&mut meta, &m.meta.build_version);
    fstr(&mut meta, &m.meta.launch_exe);
    fstr(&mut meta, &m.meta.launch_command);
    meta.extend_from_slice(&(m.meta.prereq_ids.len() as i32).to_le_bytes());
    for id in &m.meta.prereq_ids {
        fstr(&mut meta, id);
    }
    for s in &m.meta.tail {
        fstr(&mut meta, s);
    }

    let mut cdl = vec![0u8];
    cdl.extend_from_slice(&(m.chunks.len() as i32).to_le_bytes());
    for c in &m.chunks {
        for word in c.guid {
            cdl.extend_from_slice(&word.to_le_bytes());
        }
    }
    for c in &m.chunks {
        cdl.extend_from_slice(&c.rolling_hash.to_le_bytes());
    }
    for c in &m.chunks {
        cdl.extend_from_slice(&c.sha1.unwrap_or([0; 20]));
    }
    for c in &m.chunks {
        cdl.push(c.group as u8);
    }
    for c in &m.chunks {
        cdl.extend_from_slice(&(c.window as i32).to_le_bytes());
    }
    for c in &m.chunks {
        cdl.extend_from_slice(&(c.cdn_size as i64).to_le_bytes());
    }

    let mut fml = vec![0u8];
    fml.extend_from_slice(&(m.files.len() as i32).to_le_bytes());
    // `links` and `file_flags` are parallel to `files`: the layout writes one per file.
    debug_assert_eq!(m.links.len(), m.files.len(), "links must match files");
    debug_assert_eq!(m.file_flags.len(), m.files.len(), "file_flags must match files");
    debug_assert!(
        m.files.iter().all(|f| safe_save_path(&f.path)),
        "write must only be fed screened save paths"
    );
    for f in &m.files {
        fstr(&mut fml, &f.path);
    }
    for link in &m.links {
        fstr(&mut fml, link);
    }
    for f in &m.files {
        fml.extend_from_slice(&f.sha1.unwrap_or([0; 20]));
    }
    for flag in &m.file_flags {
        fml.push(*flag);
    }
    for f in &m.files {
        fml.extend_from_slice(&(f.tags.len() as i32).to_le_bytes());
        for tag in &f.tags {
            fstr(&mut fml, tag);
        }
    }
    for f in &m.files {
        fml.extend_from_slice(&(f.parts.len() as i32).to_le_bytes());
        for part in &f.parts {
            fml.extend_from_slice(&28i32.to_le_bytes());
            for word in part.guid {
                fml.extend_from_slice(&word.to_le_bytes());
            }
            fml.extend_from_slice(&part.offset.to_le_bytes());
            fml.extend_from_slice(&part.size.to_le_bytes());
        }
    }

    let mut custom = vec![m.custom.version];
    custom.extend_from_slice(&(m.custom.keys.len() as i32).to_le_bytes());
    for key in &m.custom.keys {
        fstr(&mut custom, key);
    }
    for value in &m.custom.values {
        fstr(&mut custom, value);
    }

    let mut body = Vec::new();
    section(&mut body, &meta);
    section(&mut body, &cdl);
    section(&mut body, &fml);
    section(&mut body, &custom);

    let mut out = Vec::with_capacity(HEADER_SIZE + body.len());
    out.extend_from_slice(&manifest::BINARY_MAGIC.to_le_bytes());
    out.extend_from_slice(&(HEADER_SIZE as i32).to_le_bytes());
    out.extend_from_slice(&(body.len() as i32).to_le_bytes());
    out.extend_from_slice(&(body.len() as i32).to_le_bytes());
    let sha: Sha1 = Sha1Hasher::digest(&body).into();
    out.extend_from_slice(&sha);
    out.push(0u8); // flags: never compressed
    out.extend_from_slice(&m.version.to_le_bytes());
    out.extend_from_slice(&body);
    out
}

fn fstr(out: &mut Vec<u8>, s: &str) {
    if s.is_empty() {
        out.extend_from_slice(&0i32.to_le_bytes());
    } else {
        let raw = s.as_bytes();
        out.extend_from_slice(&((raw.len() + 1) as i32).to_le_bytes());
        out.extend_from_slice(raw);
        out.push(0);
    }
}

fn section(out: &mut Vec<u8>, payload: &[u8]) {
    out.extend_from_slice(&((payload.len() + 4) as i32).to_le_bytes());
    out.extend_from_slice(payload);
}

fn inflate_body(stored: &[u8], want: usize) -> Result<Vec<u8>, String> {
    let mut out = Vec::with_capacity(want);
    flate2::read::ZlibDecoder::new(stored)
        .take(want as u64 + 1)
        .read_to_end(&mut out)
        .map_err(|e| format!("save manifest body does not inflate: {e}"))?;
    if out.len() != want {
        return Err(format!("save manifest body inflated to {} bytes, header says {want}", out.len()));
    }
    Ok(out)
}

/// What [`read`] needs beyond the shared little-endian `Reader`: the strict string slot and
/// the self-inclusive section length.
trait SaveReader<'a> {
    fn fstr(&mut self) -> Result<String, String>;
    fn section(&mut self) -> Result<&'a [u8], String>;
}

impl<'a> SaveReader<'a> for Reader<'a> {
    /// Stricter than the lenient `Reader::fstring`: it refuses what `write` could not
    /// reproduce, keeping the round trip honest.
    fn fstr(&mut self) -> Result<String, String> {
        let len = self.i32()?;
        if len == 0 {
            return Ok(String::new());
        }
        // UE's writer never emits a lone NUL as an empty string (it writes 0), so this
        // writer could not reproduce it either.
        if len == 1 {
            return Err("manifest string of a single NUL byte".into());
        }
        // EGL stores non-ASCII strings in a wide (UTF-16) form with a negative length;
        // real save manifests never carry one.
        if len < 0 {
            return Err("wide (UTF-16) manifest string".into());
        }
        let raw = self.take(len as usize)?;
        if raw[raw.len() - 1] != 0 {
            return Err("manifest string is not NUL terminated".into());
        }
        String::from_utf8(raw[..raw.len() - 1].to_vec())
            .map_err(|_| "manifest string is not UTF-8".into())
    }

    /// The length prefix covers itself: size bytes total, size-4 of payload.
    fn section(&mut self) -> Result<&'a [u8], String> {
        let size = self.i32()?;
        if size < 4 || size as usize - 4 > self.buf.len() - self.pos {
            return Err(format!("manifest section of {size} bytes at {}", self.pos - 4));
        }
        Ok(self.take(size as usize - 4)?)
    }
}

fn read_meta(data: &[u8]) -> Result<Meta, String> {
    let mut r = Reader::new(data);
    let data_version = r.u8()?;
    let feature_level = r.u32()?;
    let is_file_data = r.u8()?;
    let app_id = r.u32()?;
    let app_name = r.fstr()?;
    let build_version = r.fstr()?;
    let launch_exe = r.fstr()?;
    let launch_command = r.fstr()?;
    let id_count = r.count(4)?;
    let mut prereq_ids = Vec::with_capacity(id_count);
    for _ in 0..id_count {
        prereq_ids.push(r.fstr()?);
    }
    let mut tail = Vec::new();
    while r.pos < data.len() {
        tail.push(r.fstr()?);
    }
    Ok(Meta {
        data_version,
        feature_level,
        is_file_data,
        app_id,
        app_name,
        build_version,
        launch_exe,
        launch_command,
        prereq_ids,
        tail,
    })
}

fn read_chunk_list(data: &[u8]) -> Result<Vec<ChunkInfo>, String> {
    let mut r = Reader::new(data);
    let feature_level = r.u8()?;
    if feature_level != 0 {
        return Err(format!("unsupported chunk list feature level {feature_level}"));
    }
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
    if r.pos != data.len() {
        return Err("chunk list has unrecognised bytes".into());
    }
    Ok((0..count)
        .map(|i| ChunkInfo {
            guid: guids[i],
            rolling_hash: rolling[i],
            // Kept verbatim (even all-zero) so the written chunk list matches the read one.
            sha1: Some(hashes[i]),
            group: groups[i] as u64,
            window: windows[i] as u32,
            window_exact: true,
            cdn_size: sizes[i] as u64,
        })
        .collect())
}

fn read_file_list(data: &[u8]) -> Result<(Vec<FileEntry>, Vec<String>, Vec<u8>), String> {
    let mut r = Reader::new(data);
    let feature_level = r.u8()?;
    if feature_level != 0 {
        return Err(format!("unsupported file list feature level {feature_level}"));
    }
    let count = r.count(4 + 4 + 20 + 1 + 4 + 4)?;
    let mut names = Vec::with_capacity(count);
    for _ in 0..count {
        names.push(r.fstr()?);
    }
    let mut links = Vec::with_capacity(count);
    for _ in 0..count {
        links.push(r.fstr()?);
    }
    let mut hashes = Vec::with_capacity(count);
    for _ in 0..count {
        hashes.push(r.sha1()?);
    }
    let mut flags = Vec::with_capacity(count);
    for _ in 0..count {
        flags.push(r.u8()?);
    }
    let mut tags = Vec::with_capacity(count);
    for _ in 0..count {
        let n = r.count(4)?;
        let mut list = Vec::with_capacity(n);
        for _ in 0..n {
            list.push(r.fstr()?);
        }
        tags.push(list);
    }
    let mut files = Vec::with_capacity(count);
    for (i, (path, tag_list)) in names.into_iter().zip(tags).enumerate() {
        let n = r.count(28)?;
        let mut parts = Vec::with_capacity(n);
        let mut size = 0u64;
        for _ in 0..n {
            if r.i32()? != 28 {
                return Err("chunk part record is not 28 bytes".into());
            }
            let guid = r.guid()?;
            let offset = r.u32()?;
            let len = r.u32()?;
            size += len as u64;
            parts.push(PartRef { guid, offset, size: len });
        }
        files.push(FileEntry {
            path,
            sha1: Some(hashes[i]),
            tags: tag_list,
            parts,
            size,
        });
    }
    if r.pos != data.len() {
        return Err("file list has unrecognised bytes".into());
    }
    Ok((files, links, flags))
}

fn read_custom(data: &[u8]) -> Result<CustomFields, String> {
    let mut r = Reader::new(data);
    let version = r.u8()?;
    let count = r.count(4)?;
    let mut keys = Vec::with_capacity(count);
    for _ in 0..count {
        keys.push(r.fstr()?);
    }
    let mut values = Vec::new();
    while r.pos < data.len() {
        values.push(r.fstr()?);
    }
    Ok(CustomFields { version, keys, values })
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::epic::savesync;
    use std::collections::HashMap;
    use std::fs;
    use std::path::{Path, PathBuf};

    fn guid_of(index: usize) -> Guid {
        [0x1000_0000 + index as u32, 0x2222_2222, 0x3333_3333, 0x4444_0000 + index as u32]
    }

    /// A test manifest over `files` (given in packing order), packed by `BlockPacker` with
    /// deterministic GUIDs and chunked the way a publish would: the model, its bytes and the
    /// chunk files.
    fn build_manifest(files: &[(&str, Vec<u8>)]) -> (SaveManifest, Vec<u8>, Vec<Vec<u8>>) {
        let mut blocks: Vec<Vec<u8>> = Vec::new();
        let mut on_block = |index: usize, block: &[u8]| -> Result<Guid, String> {
            assert_eq!(index, blocks.len(), "one call per block, in block order");
            blocks.push(block.to_vec());
            Ok(guid_of(index))
        };
        let mut packer = BlockPacker::new();
        for (path, content) in files {
            packer.push(path, content, &mut on_block).unwrap();
        }
        let packed_files = packer.finish(&mut on_block).unwrap();
        let mut chunks = Vec::new();
        let mut chunk_bytes = Vec::new();
        for (index, block) in blocks.iter().enumerate() {
            let built = chunk::encode(block, &guid_of(index)).unwrap();
            chunks.push(built.info(&guid_of(index)));
            chunk_bytes.push(built.bytes);
        }
        let count = packed_files.len();
        let m = SaveManifest {
            version: FORMAT_VERSION,
            meta: Meta {
                data_version: 0,
                feature_level: 0,
                is_file_data: 1,
                app_id: 42,
                app_name: "AppName".into(),
                build_version: "2026.11.28-12.34.56".into(),
                launch_exe: String::new(),
                launch_command: String::new(),
                prereq_ids: vec!["prereq-id".into()],
                tail: vec![String::new(), String::new(), String::new()],
            },
            chunks,
            files: packed_files,
            links: vec![String::new(); count],
            file_flags: vec![0; count],
            custom: CustomFields { version: 0, keys: vec!["BackupLimit".into()], values: vec!["3".into()] },
        };
        let bytes = write(&m);
        (m, bytes, chunk_bytes)
    }

    #[test]
    fn packed_saves_round_trip_through_write_read_and_assemble() {
        // One file longer than the window (it spans two blocks and leaves room for the other
        // files in the second block), an empty file, a nested name and two names differing only
        // in case, all listed in EGL's case-insensitive order.
        let big: Vec<u8> = (0..chunk::CHUNK_WINDOW + 4096).map(|i| (((i % 251) * 7) % 256) as u8).collect();
        let files: Vec<(&str, Vec<u8>)> = vec![
            ("Big/level.dat", big),
            ("Case/A.txt", b"upper".to_vec()),
            ("Case/a.txt", b"lower".to_vec()),
            ("nested/dir/file.bin", b"nested".to_vec()),
            ("Saves/empty.txt", Vec::new()),
            ("Saves/slot1.sav", b"slot one".to_vec()),
        ];
        let (m, bytes, chunk_bytes) = build_manifest(&files);

        let back = read(&bytes).unwrap();
        assert_eq!(back, m);
        assert_eq!(write(&back), bytes);
        assert_eq!(back.version, 17);
        assert_eq!(back.chunks.len(), 2);
        assert_eq!(back.meta.tail, vec!["", "", ""]);
        assert_eq!(back.custom.keys, vec!["BackupLimit"]);
        let order: Vec<&str> = back.files.iter().map(|f| f.path.as_str()).collect();
        assert_eq!(order, ["Big/level.dat", "Case/A.txt", "Case/a.txt", "nested/dir/file.bin", "Saves/empty.txt", "Saves/slot1.sav"]);
        let empty = back.files.iter().find(|f| f.path == "Saves/empty.txt").unwrap();
        assert!(empty.parts.is_empty() && empty.size == 0);

        // The pre-existing read path must reproduce the very same save files.
        let parsed = manifest::parse(&bytes).unwrap();
        assert_eq!(parsed.files.len(), files.len());
        let raw_of: HashMap<Guid, &Vec<u8>> =
            m.chunks.iter().zip(&chunk_bytes).map(|(c, b)| (c.guid, b)).collect();
        let mut blocks: HashMap<Guid, Vec<u8>> = HashMap::new();
        for info in &parsed.chunks {
            let raw = raw_of.get(&info.guid).expect("every parsed chunk was built");
            let block = chunk::decode(raw, info).unwrap();
            assert_eq!(block.len(), chunk::CHUNK_WINDOW, "a block is always the full window");
            blocks.insert(info.guid, block);
        }
        // The stream ends 4096 + 26 bytes into the second block; the rest is zero padding.
        let used: usize = files.iter().map(|(_, content)| content.len()).sum::<usize>() - chunk::CHUNK_WINDOW;
        let last = &blocks[&guid_of(1)];
        assert!(last[..used].iter().any(|&b| b != 0) && last[used..].iter().all(|&b| b == 0), "the last block is zero-padded");
        let assembled = savesync::assemble(&parsed.files, |part| {
            blocks.get(&part.guid).cloned().ok_or_else(|| "missing chunk".into())
        })
        .unwrap();
        assert_eq!(assembled.len(), files.len());
        // Assemble returns files in manifest order, i.e. the packing order.
        for (out, (path, content)) in assembled.iter().zip(&files) {
            assert_eq!(out.name, *path, "assembled order");
            assert_eq!(out.content, *content, "content of {path}");
        }
    }

    #[test]
    fn a_compressed_manifest_reads_and_normalises_to_uncompressed() {
        use std::io::Write as _;
        let (m, uncompressed, _) = build_manifest(&[("Saves/a.sav", b"payload".to_vec())]);
        // Hand-wrap the very same body compressed, the way EGL stored some manifests.
        let body = &uncompressed[HEADER_SIZE..];
        let mut encoder = flate2::write::ZlibEncoder::new(Vec::new(), flate2::Compression::new(6));
        encoder.write_all(body).unwrap();
        let compressed = encoder.finish().unwrap();
        let mut x = Vec::new();
        x.extend_from_slice(&manifest::BINARY_MAGIC.to_le_bytes());
        x.extend_from_slice(&41i32.to_le_bytes());
        x.extend_from_slice(&(body.len() as i32).to_le_bytes());
        x.extend_from_slice(&(compressed.len() as i32).to_le_bytes());
        let sha: Sha1 = Sha1Hasher::digest(body).into();
        x.extend_from_slice(&sha);
        x.push(1u8);
        x.extend_from_slice(&FORMAT_VERSION.to_le_bytes());
        x.extend_from_slice(&compressed);
        assert_eq!(x[36], 1);
        // Reading keeps the model; writing normalises to EGL's own uncompressed layout.
        assert_eq!(read(&x).unwrap(), m);
        let again = write(&read(&x).unwrap());
        assert_eq!(again, uncompressed);
        // Everything but storedSize and the flags byte matches the compressed original.
        assert_eq!(&again[0..12], &x[0..12]);
        assert_eq!(&again[16..36], &x[16..36]);
        assert_eq!(&again[37..41], &x[37..41]);
        assert_eq!(again[36], 0);
        assert_eq!(&again[HEADER_SIZE..], body);
    }

    fn one_file_bytes() -> Vec<u8> {
        build_manifest(&[("Saves/a.sav", b"abc".to_vec())]).1
    }

    fn wrap_body(body: &[u8]) -> Vec<u8> {
        let mut out = Vec::new();
        out.extend_from_slice(&manifest::BINARY_MAGIC.to_le_bytes());
        out.extend_from_slice(&41i32.to_le_bytes());
        out.extend_from_slice(&(body.len() as i32).to_le_bytes());
        out.extend_from_slice(&(body.len() as i32).to_le_bytes());
        let sha: Sha1 = Sha1Hasher::digest(body).into();
        out.extend_from_slice(&sha);
        out.push(0u8);
        out.extend_from_slice(&FORMAT_VERSION.to_le_bytes());
        out.extend_from_slice(body);
        out
    }

    /// A manifest body whose meta holds the four empty strings, no prereq ids and then
    /// `meta_tail`, over an empty chunk list, the given file list and an empty custom section.
    fn body_with(meta_tail: &[u8], file_list: &[u8]) -> Vec<u8> {
        let mut meta = vec![0u8];
        meta.extend_from_slice(&0u32.to_le_bytes());
        meta.push(0u8);
        meta.extend_from_slice(&0u32.to_le_bytes());
        for _ in 0..4 {
            meta.extend_from_slice(&0i32.to_le_bytes());
        }
        meta.extend_from_slice(&0i32.to_le_bytes());
        meta.extend_from_slice(meta_tail);
        let mut body = Vec::new();
        let empty = [0u8, 0, 0, 0, 0];
        section(&mut body, &meta);
        section(&mut body, &empty);
        section(&mut body, file_list);
        section(&mut body, &empty);
        body
    }

    #[test]
    fn damaged_manifests_are_refused() {
        let good = one_file_bytes();
        // Unknown format version.
        let mut other_version = good.clone();
        other_version[37..41].copy_from_slice(&16u32.to_le_bytes());
        assert!(read(&other_version).is_err());
        // Truncated at various lengths, including inside the header.
        for cut in [30, HEADER_SIZE, good.len() - 1] {
            assert!(read(&good[..cut]).is_err(), "cut at {cut}");
        }
        // Encrypted flag, or an unknown flag bit that could mean a layout we do not know.
        let mut flags = good.clone();
        flags[36] |= 2;
        assert!(read(&flags).is_err());
        flags[36] = 4;
        assert!(read(&flags).is_err());
        // A header size other than the strict 41.
        let mut header_size = good.clone();
        header_size[4..8].copy_from_slice(&45i32.to_le_bytes());
        assert!(read(&header_size).is_err());
        // A stored size that does not match the trailing bytes.
        let mut stored = good.clone();
        stored[12..16].copy_from_slice(&(good.len() as i32).to_le_bytes());
        assert!(read(&stored).is_err());
        // Corrupt body (SHA-1 mismatch).
        let mut corrupt = good.clone();
        let last = corrupt.len() - 1;
        corrupt[last] ^= 0xFF;
        assert!(read(&corrupt).is_err());
        // A lone NUL as an "empty string" (length 1): UE writes 0 instead, and so do we.
        assert!(read(&wrap_body(&body_with(&[1, 0, 0, 0, 0], &[]))).is_err());
        // Not a binary manifest at all.
        assert!(read(br#"{"FileManifestList": []}"#).is_err());
    }

    #[test]
    fn out_of_bounds_sections_are_refused() {
        // The header describes the body honestly, so the refusal is really about the
        // section length and not about the SHA-1 or the stored size.
        let mut huge = 0x7FFF_FFFFi32.to_le_bytes().to_vec();
        huge.extend_from_slice(&[0u8; 8]);
        let err = read(&wrap_body(&huge)).unwrap_err();
        assert!(err.contains("section"), "{err}");
        let mut negative = (-1i32).to_le_bytes().to_vec();
        negative.extend_from_slice(&[0u8; 8]);
        let err = read(&wrap_body(&negative)).unwrap_err();
        assert!(err.contains("section"), "{err}");
    }

    #[test]
    fn save_names_are_screened() {
        for good in ["Saves/slot1.sav", "a/b/c.bin", "x.sav"] {
            assert_eq!(validate_save_name(good), Ok(()), "{good}");
        }
        // Not a clean relative path, or not ASCII.
        for bad in ["", "/abs", "../up", "a/../b", "a//b", "./x", "back\\slash", ".", "caf\u{e9}.sav"] {
            assert!(validate_save_name(bad).is_err(), "{bad:?}");
        }
        // The packer screens every name it is fed.
        assert!(BlockPacker::new().push("../x.sav", b"", &mut |_, _| Ok([0; 4])).is_err());
        // A manifest that lists an unsafe path does not survive a read either. Built by
        // hand: write() debug-asserts it is only fed screened paths.
        let mut fml = vec![0u8];
        fml.extend_from_slice(&1i32.to_le_bytes());
        fstr(&mut fml, "../evil.sav");
        fstr(&mut fml, "");
        fml.extend_from_slice(&[0u8; 20]);
        fml.push(0u8);
        fml.extend_from_slice(&0i32.to_le_bytes());
        fml.extend_from_slice(&0i32.to_le_bytes());
        assert!(read(&wrap_body(&body_with(&[], &fml))).is_err());
    }

    /// A fixed synthetic model whose every byte — down to the chunk's CDN size, which is
    /// EGL's zlib value rather than anything our own compressor produces — was generated
    /// with the byte-verified reference implementation (`verify_format.py` in the
    /// capture workspace outside this repository). This
    /// runs un-ignored so the test gate pins the wire format even without real samples.
    #[test]
    fn golden_manifest_matches_the_reference_implementation() {
        use sha2::Digest as _;
        let m = SaveManifest {
            version: FORMAT_VERSION,
            meta: Meta {
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
            chunks: vec![ChunkInfo {
                guid: [0xA000, 1, 2, 3],
                rolling_hash: 0xFE6B_B188_85AA_72C7,
                sha1: Some([
                    0x2a, 0x59, 0xa9, 0x42, 0x87, 0x89, 0xbd, 0x1f, 0xf4, 0x14, 0xec, 0xd6,
                    0x9d, 0x13, 0xf3, 0x50, 0xe5, 0xf3, 0x8d, 0xc0,
                ]),
                group: 16,
                window: 1024 * 1024,
                window_exact: true,
                cdn_size: 1108,
            }],
            files: vec![FileEntry {
                path: "a.txt".into(),
                sha1: Some([
                    0x40, 0xbd, 0x00, 0x15, 0x63, 0x08, 0x5f, 0xc3, 0x51, 0x65, 0x32, 0x9e,
                    0xa1, 0xff, 0x5c, 0x5e, 0xcb, 0xdb, 0xbe, 0xef,
                ]),
                tags: Vec::new(),
                parts: vec![PartRef { guid: [0xA000, 1, 2, 3], offset: 0, size: 3 }],
                size: 3,
            }],
            links: vec![String::new()],
            file_flags: vec![0],
            custom: CustomFields { version: 0, keys: Vec::new(), values: Vec::new() },
        };
        let bytes = write(&m);
        let hex: String = sha2::Sha256::digest(&bytes).iter().map(|b| format!("{b:02x}")).collect();
        assert_eq!(hex, "5e9a356001b3f87f84b943a882a014f12437fa17292ef68475c6c1acc7a95e33");
        assert_eq!(bytes.len(), 266);
        // The model survives its own bytes, and the re-encode is stable.
        assert_eq!(read(&bytes).unwrap(), m);
        assert_eq!(write(&read(&bytes).unwrap()), bytes);
    }

    /// Second golden model, exercising the non-empty shapes the first one leaves at their
    /// defaults: two chunks, a cross-block file with two parts, a tag, a non-zero file
    /// flag, a non-empty link, a prereq id and custom keys/values. Same provenance as the
    /// first golden test.
    #[test]
    fn golden_manifest_with_full_fields_matches_the_reference() {
        use sha2::Digest as _;
        let content_a: Vec<u8> =
            (0..chunk::CHUNK_WINDOW - 4).map(|i| (((i % 251) * 7) % 256) as u8).collect();
        let m = SaveManifest {
            version: FORMAT_VERSION,
            meta: Meta {
                data_version: 0,
                feature_level: 0,
                is_file_data: 1,
                app_id: 7,
                app_name: "App".into(),
                build_version: "2026.01.01-00.00.00".into(),
                launch_exe: String::new(),
                launch_command: String::new(),
                prereq_ids: vec!["prereq-id".into()],
                tail: vec![String::new(), String::new(), String::new()],
            },
            chunks: vec![
                ChunkInfo {
                    guid: [0xB000, 1, 2, 3],
                    rolling_hash: 0xE3E7_3C11_AA3A_7A94,
                    sha1: Some([
                        0x3f, 0x30, 0x31, 0x22, 0x78, 0x2c, 0x7e, 0x97, 0x9b, 0x89, 0x5a, 0xde,
                        0x16, 0x6a, 0x54, 0x96, 0xdb, 0xdc, 0xd8, 0x9d,
                    ]),
                    group: 23,
                    window: 1024 * 1024,
                    window_exact: true,
                    cdn_size: 4462,
                },
                ChunkInfo {
                    guid: [0xB001, 1, 2, 3],
                    rolling_hash: 0x251D_2E9F_4ABB_50FF,
                    sha1: Some([
                        0xfd, 0xe7, 0xcc, 0xd2, 0xcd, 0x05, 0x7b, 0x49, 0x38, 0xc4, 0xfc, 0x14,
                        0xf0, 0x7a, 0x8c, 0xf8, 0x0e, 0x46, 0xeb, 0xcd,
                    ]),
                    group: 2,
                    window: 1024 * 1024,
                    window_exact: true,
                    cdn_size: 1109,
                },
            ],
            files: vec![
                FileEntry {
                    path: "Big/a.dat".into(),
                    sha1: Some([
                        0x4d, 0x6b, 0x81, 0x85, 0x08, 0x04, 0x9b, 0xed, 0x02, 0x3e, 0x2f, 0xf6,
                        0x30, 0xf9, 0xce, 0x72, 0x35, 0xc7, 0x76, 0xa9,
                    ]),
                    tags: Vec::new(),
                    parts: vec![PartRef {
                        guid: [0xB000, 1, 2, 3],
                        offset: 0,
                        size: (chunk::CHUNK_WINDOW - 4) as u32,
                    }],
                    size: (chunk::CHUNK_WINDOW - 4) as u64,
                },
                FileEntry {
                    path: "b.txt".into(),
                    sha1: Some([
                        0x7c, 0x22, 0x2f, 0xb2, 0x92, 0x7d, 0x82, 0x8a, 0xf2, 0x2f, 0x59, 0x21,
                        0x34, 0xe8, 0x93, 0x24, 0x80, 0x63, 0x7c, 0x0d,
                    ]),
                    tags: vec!["Backups".into()],
                    parts: vec![
                        PartRef { guid: [0xB000, 1, 2, 3], offset: (chunk::CHUNK_WINDOW - 4) as u32, size: 4 },
                        PartRef { guid: [0xB001, 1, 2, 3], offset: 0, size: 4 },
                    ],
                    size: 8,
                },
            ],
            links: vec![String::new(), "OldTarget".into()],
            file_flags: vec![0, 1],
            custom: CustomFields {
                version: 2,
                keys: vec!["BackupLimit".into(), "BackupDelay".into()],
                values: vec!["3".into(), "7".into()],
            },
        };
        // The reconstructed first file must hash to the constant the reference produced,
        // proving this model feeds the reference the very bytes the constants describe.
        let sha_a: Sha1 = Sha1Hasher::digest(&content_a).into();
        assert_eq!(sha_a, m.files[0].sha1.unwrap());
        let bytes = write(&m);
        let hex: String = sha2::Sha256::digest(&bytes).iter().map(|b| format!("{b:02x}")).collect();
        assert_eq!(hex, "11914966740b60d51a7fc30260ccb0013910cbc96644b7e1c246ef936639aee6");
        assert_eq!(bytes.len(), 506);
        assert_eq!(read(&bytes).unwrap(), m);
        assert_eq!(write(&read(&bytes).unwrap()), bytes);
    }

    // ---- real EGL captures, opt-in via DD_EPIC_SAVE_SAMPLES ----

    fn samples_root() -> Option<PathBuf> {
        let Some(value) = std::env::var_os("DD_EPIC_SAVE_SAMPLES") else {
            return None;
        };
        // A set-but-wrong path must not silently pass without comparing anything.
        let root = PathBuf::from(value);
        assert!(
            root.is_dir(),
            "DD_EPIC_SAVE_SAMPLES={} is not a directory",
            root.display()
        );
        Some(root)
    }

    /// Every `<game>/cloud/manifests/*.manifest` in the capture, sorted, with its game dir.
    fn sample_manifests(root: &Path) -> Vec<(PathBuf, Vec<PathBuf>)> {
        let mut games: Vec<(PathBuf, Vec<PathBuf>)> = Vec::new();
        let Ok(entries) = fs::read_dir(root) else {
            return games;
        };
        for game in entries.flatten() {
            let dir = game.path().join("cloud").join("manifests");
            let Ok(list) = fs::read_dir(&dir) else { continue };
            let mut found: Vec<PathBuf> = list
                .flatten()
                .map(|e| e.path())
                .filter(|p| p.extension().map_or(false, |e| e == "manifest"))
                .collect();
            found.sort();
            if !found.is_empty() {
                games.push((game.path(), found));
            }
        }
        games.sort();
        games
    }

    fn inflate_block(stored: &[u8]) -> Result<Vec<u8>, String> {
        let mut out = Vec::new();
        flate2::read::ZlibDecoder::new(stored)
            .read_to_end(&mut out)
            .map_err(|e| format!("chunk payload does not inflate: {e}"))?;
        Ok(out)
    }

    /// `write(read(bytes))` must equal `bytes` for an uncompressed manifest. EGL also wrote
    /// a few compressed (flags bit 0) manifests; `write` normalises those to uncompressed
    /// (our pure-Rust deflate backend cannot reproduce zlib's exact bytes anyway), so there
    /// everything BUT the compressor's output must still match: the header fields, the
    /// inflated body, and the parsed model.
    fn assert_round_trip(path: &Path, bytes: &[u8], again: &[u8], m: &SaveManifest) {
        if bytes[36] & 1 == 0 {
            assert_eq!(again, bytes, "{} does not round trip", path.display());
            return;
        }
        assert_eq!(read(again).as_ref(), Ok(m), "{} reads back differently", path.display());
        assert_eq!(&again[0..12], &bytes[0..12], "{} header prefix", path.display());
        assert_eq!(&again[16..36], &bytes[16..36], "{} body SHA-1", path.display());
        assert_eq!(&again[37..41], &bytes[37..41], "{} version", path.display());
        assert_eq!(again[36], 0, "{} flags byte", path.display());
        let original = inflate_block(&bytes[HEADER_SIZE..]).unwrap();
        assert_eq!(&again[HEADER_SIZE..], &original[..], "{} compressed body", path.display());
    }

    #[test]
    #[ignore]
    fn real_samples_manifests_round_trip_byte_for_byte() {
        let Some(root) = samples_root() else { return };
        let games = sample_manifests(&root);
        assert!(!games.is_empty(), "no manifests under {}", root.display());
        let mut manifests = 0;
        for (_, paths) in &games {
            for path in paths {
                let bytes = fs::read(path).unwrap();
                let m = read(&bytes).unwrap_or_else(|e| panic!("{}: {e}", path.display()));
                let again = write(&m);
                assert_round_trip(path, &bytes, &again, &m);
                manifests += 1;
            }
        }
        println!("real_samples_manifests_round_trip_byte_for_byte: {manifests} manifests compared");
    }

    #[test]
    #[ignore]
    fn real_samples_chunks_match_egl_header_and_hashes() {
        let Some(root) = samples_root() else { return };
        let mut count = 0;
        for (game, _) in sample_manifests(&root) {
            let chunks_root = game.join("cloud").join("ChunksV4");
            let Ok(groups) = fs::read_dir(&chunks_root) else { continue };
            for group in groups.flatten() {
                let Ok(files) = fs::read_dir(group.path()) else { continue };
                for file in files.flatten() {
                    let path = file.path();
                    if !path.extension().map_or(false, |e| e == "chunk") {
                        continue;
                    }
                    let raw = fs::read(&path).unwrap();
                    assert!(raw.len() >= 66, "{} is too short", path.display());
                    let mut guid = [0u32; 4];
                    for (i, slot) in guid.iter_mut().enumerate() {
                        let at = 16 + i * 4;
                        *slot = u32::from_le_bytes(raw[at..at + 4].try_into().unwrap());
                    }
                    // The folder number is crc32(GUID) % 100, matching the group vectors.
                    let folder: u8 = group.file_name().to_string_lossy().parse().unwrap();
                    assert_eq!(folder, chunk::guid_group(&guid), "{} group", path.display());
                    let block = inflate_block(&raw[66..]).unwrap();
                    let built = chunk::encode(&block, &guid).unwrap();
                    // Both hashes cover the padded block, so the header must match EGL's
                    // everywhere except the stored length: that one field (and the payload
                    // bytes behind it) is the compressor's choice, and our pure-Rust
                    // deflate backend differs from EGL's zlib on purpose.
                    assert_eq!(&built.bytes[0..12], &raw[0..12], "{} header prefix", path.display());
                    assert_eq!(&built.bytes[16..66], &raw[16..66], "{} header tail", path.display());
                    assert_eq!(
                        built.bytes[12..16],
                        (built.stored as u32).to_le_bytes(),
                        "{} stored length field",
                        path.display()
                    );
                    // Hooked to the sample: EGL's own stored length describes its file,
                    // and its SHA-1 field describes the very block we just inflated.
                    let egl_stored = i32::from_le_bytes(raw[12..16].try_into().unwrap());
                    assert_eq!(egl_stored as usize, raw.len() - 66, "{} EGL stored", path.display());
                    let rolling = u64::from_le_bytes(raw[32..40].try_into().unwrap());
                    assert_eq!(built.rolling, rolling, "{} rolling hash", path.display());
                    let egl_sha: Sha1 = raw[41..61].try_into().unwrap();
                    assert_eq!(built.sha1, egl_sha, "{} SHA-1 field", path.display());
                    // Our payload is not byte-identical to EGL's, but it must inflate back
                    // to the very same block.
                    assert_eq!(
                        inflate_block(&built.bytes[66..]).unwrap(),
                        block,
                        "{} own payload",
                        path.display()
                    );
                    count += 1;
                }
            }
        }
        assert!(count >= 1, "no chunk files under {}", root.display());
        println!("real_samples_chunks_match_egl_header_and_hashes: {count} chunk files compared");
    }

    #[test]
    #[ignore]
    fn real_samples_repack_to_identical_manifests() {
        let Some(root) = samples_root() else { return };
        let games = sample_manifests(&root);
        assert!(!games.is_empty(), "no manifests under {}", root.display());
        let mut rebuildable = 0;
        let mut total = 0;
        for (game, paths) in &games {
            for path in paths {
                total += 1;
                let bytes = fs::read(path).unwrap();
                let m = read(&bytes).unwrap();
                let cloud = game.join("cloud");
                let mut blocks: HashMap<Guid, Vec<u8>> = HashMap::with_capacity(m.chunks.len());
                let mut missing = false;
                for info in &m.chunks {
                    let file = cloud.join(chunk_cloud_path(info));
                    match fs::read(&file) {
                        Ok(raw) => {
                            let block = inflate_block(&raw[66..]).unwrap();
                            let sha: Sha1 = Sha1Hasher::digest(&block).into();
                            assert_eq!(sha, info.sha1.unwrap_or([0; 20]), "{} content", file.display());
                            assert_eq!(chunk::rolling_hash(&block), info.rolling_hash, "{} rolling", file.display());
                            blocks.insert(info.guid, block);
                        }
                        Err(_) => {
                            // EGL pruned this older manifest's chunks; its bytes cannot be rebuilt.
                            missing = true;
                            break;
                        }
                    }
                }
                if missing {
                    continue;
                }
                let contents: Vec<Vec<u8>> = m
                    .files
                    .iter()
                    .map(|f| {
                        let mut content = Vec::new();
                        for part in &f.parts {
                            let block = &blocks[&part.guid];
                            content.extend_from_slice(&block[part.offset as usize..part.offset as usize + part.size as usize]);
                        }
                        content
                    })
                    .collect();
                for (f, content) in m.files.iter().zip(&contents) {
                    let sha: Sha1 = Sha1Hasher::digest(content).into();
                    assert_eq!(Some(sha), f.sha1, "{} decodes to other bytes", f.path);
                }
                // Fed in manifest order, the packer must cut the same blocks: one per chunk.
                let mut sealed = 0usize;
                let mut on_block = |index: usize, _: &[u8]| {
                    assert_eq!(index, sealed, "{}", path.display());
                    sealed += 1;
                    Ok(m.chunks[index].guid)
                };
                let mut packer = BlockPacker::new();
                for (f, content) in m.files.iter().zip(&contents) {
                    packer.push(&f.path, content, &mut on_block).unwrap();
                }
                let packed_files = packer.finish(&mut on_block).unwrap();
                assert_eq!(sealed, m.chunks.len(), "{} block count", path.display());
                // Everything but the file list stays the original's, chunk CDN sizes
                // included. Compare against the canonical (uncompressed) form of the
                // original, since write always normalises compressed originals to flags=0.
                let canonical = write(&m);
                let mut rebuilt = m.clone();
                rebuilt.files = packed_files;
                assert_eq!(write(&rebuilt), canonical, "{} does not rebuild", path.display());
                rebuildable += 1;
            }
        }
        assert!(rebuildable >= 1, "none of {total} manifests rebuildable");
        println!(
            "real_samples_repack_to_identical_manifests: {rebuildable} of {total} manifests rebuilt identically"
        );
    }
}
