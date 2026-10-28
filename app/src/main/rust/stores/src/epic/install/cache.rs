use std::collections::HashMap;
use std::collections::HashSet;
use std::fs;
use std::io::{Read, Seek, SeekFrom, Write};
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, Ordering};

use sha1::{Digest, Sha1};

use crate::epic::manifest::{guid_from_hex, guid_text, ChunkInfo, Manifest};

pub fn entry_name(chunk: &ChunkInfo, copy: u32) -> String {
    if copy == 0 {
        guid_text(&chunk.guid)
    } else {
        format!("{}.{copy}", guid_text(&chunk.guid))
    }
}

pub fn store(dir: &Path, name: &str, data: &[u8]) -> Result<PathBuf, String> {
    fs::create_dir_all(dir).map_err(|e| format!("chunk cache: {e}"))?;
    let path = dir.join(name);
    let part = dir.join(format!("{name}.part"));
    let result = (|| -> std::io::Result<()> {
        let mut f = fs::File::create(&part)?;
        f.write_all(data)?;
        f.flush()?;
        fs::rename(&part, &path)
    })();
    if let Err(e) = result {
        let _ = fs::remove_file(&part);
        return Err(format!("chunk cache write: {e}"));
    }
    Ok(path)
}

pub fn remove_partials(dir: &Path) {
    if let Ok(entries) = fs::read_dir(dir) {
        for e in entries.flatten() {
            if e.file_name().to_string_lossy().ends_with(".part") {
                let _ = fs::remove_file(e.path());
            }
        }
    }
}

fn remove_entry(path: &Path, meta: &fs::Metadata) {
    if meta.is_dir() && !meta.file_type().is_symlink() {
        let _ = fs::remove_dir_all(path);
    } else {
        let _ = fs::remove_file(path);
    }
}

fn sha1_of_file(path: &Path, cancel: &AtomicBool) -> Option<[u8; 20]> {
    let mut f = fs::File::open(path).ok()?;
    let mut h = Sha1::new();
    let mut buf = vec![0u8; 256 * 1024];
    loop {
        if cancel.load(Ordering::Relaxed) {
            return None;
        }
        let n = f.read(&mut buf).ok()?;
        if n == 0 {
            break;
        }
        h.update(&buf[..n]);
    }
    Some(h.finalize().into())
}

pub fn prepare(
    dir: &Path,
    chunks: &[ChunkInfo],
    needed: &HashMap<u32, u32>,
    budget: u64,
    cancel: &AtomicBool,
) -> Vec<u32> {
    let Ok(entries) = fs::read_dir(dir) else {
        return Vec::new();
    };
    let by_name: HashMap<String, u32> = needed
        .keys()
        .map(|&c| (guid_text(&chunks[c as usize].guid), c))
        .collect();
    let mut candidates: Vec<(u32, u32, PathBuf)> = Vec::new();
    for entry in entries.flatten() {
        let path = entry.path();
        let Ok(meta) = fs::symlink_metadata(&path) else {
            continue;
        };
        let name = entry.file_name().to_string_lossy().into_owned();
        let chunk = by_name.get(&name).copied().filter(|_| name.len() == 32 && guid_from_hex(&name).is_some());
        let usable = meta.is_file() && !meta.file_type().is_symlink();
        match chunk {
            Some(c) if usable => {
                let info = &chunks[c as usize];
                let len_ok = if info.window_exact {
                    meta.len() == info.window as u64
                } else {
                    meta.len() > 0 && meta.len() <= info.window as u64
                };
                if len_ok {
                    candidates.push((needed[&c], c, path));
                } else {
                    remove_entry(&path, &meta);
                }
            }
            _ => remove_entry(&path, &meta),
        }
    }
    candidates.sort_unstable_by_key(|(first, _, _)| *first);
    let mut kept = Vec::new();
    let mut used = 0u64;
    for (_, c, path) in candidates {
        let info = &chunks[c as usize];
        let size = fs::metadata(&path).map(|m| m.len()).unwrap_or(u64::MAX);
        let fits = used.saturating_add(size) <= budget;
        let hash_ok = fits
            && match info.sha1 {
                Some(expected) => sha1_of_file(&path, cancel) == Some(expected),
                None => true,
            };
        if hash_ok {
            used += size;
            kept.push(c);
        } else {
            let _ = fs::remove_file(&path);
        }
    }
    kept
}

struct Rebuilt {
    bytes: Vec<u8>,
    covered: Vec<bool>,
    valid: bool,
}

pub fn seed_from_verified_installed(
    dir: &Path,
    game_dir: &Path,
    installed: &Manifest,
    target: &Manifest,
    needed: &HashSet<u32>,
    cached: &[u32],
    budget: u64,
    cancel: &AtomicBool,
) -> (Vec<u32>, u64) {
    const MAX_WINDOW: u32 = 64 * 1024 * 1024;
    const MAX_REBUILD_MEMORY: u64 = 32 * 1024 * 1024;
    let cached: HashSet<u32> = cached.iter().copied().collect();
    let mut rebuilt: HashMap<u32, Rebuilt> = HashMap::new();
    let mut reserved = 0u64;
    let budget = budget.min(MAX_REBUILD_MEMORY);
    for file in &installed.files {
        if cancel.load(Ordering::Relaxed) {
            break;
        }
        let Some(expected) = file.sha1 else { continue };
        if !super::safe_relative(&file.path) {
            continue;
        }
        let path = game_dir.join(&file.path);
        let mut safe = game_dir.to_path_buf();
        let mut no_links = true;
        for segment in file.path.split('/') {
            safe.push(segment);
            if fs::symlink_metadata(&safe)
                .map(|m| m.file_type().is_symlink())
                .unwrap_or(false)
            {
                no_links = false;
                break;
            }
        }
        if !no_links {
            continue;
        }
        let Ok(meta) = fs::symlink_metadata(&path) else {
            continue;
        };
        if !meta.is_file() || meta.file_type().is_symlink() {
            continue;
        }
        match super::delta::check(&path, file.size, Some(expected), cancel) {
            super::delta::Check::Cancelled => break,
            super::delta::Check::UpToDate => {}
            super::delta::Check::Pending { .. } => continue,
        }
        let mut source = match fs::File::open(&path) {
            Ok(f) => f,
            Err(_) => continue,
        };
        let mut file_offset = 0u64;
        for part in &file.parts {
            let Some(&target_index) = target.chunk_index.get(&part.guid) else {
                file_offset += part.size as u64;
                continue;
            };
            let target_index = target_index as u32;
            if !needed.contains(&target_index) || cached.contains(&target_index) {
                file_offset += part.size as u64;
                continue;
            }
            let info = &target.chunks[target_index as usize];
            if !info.window_exact
                || info.window > MAX_WINDOW
                || part.offset.saturating_add(part.size) > info.window
            {
                file_offset += part.size as u64;
                continue;
            }
            if !rebuilt.contains_key(&target_index) {
                let size = info.window as u64;
                if reserved.saturating_add(size) > budget {
                    file_offset += part.size as u64;
                    continue;
                }
                reserved += size;
                rebuilt.insert(
                    target_index,
                    Rebuilt {
                        bytes: vec![0; info.window as usize],
                        covered: vec![false; info.window as usize],
                        valid: true,
                    },
                );
            }
            let Some(chunk) = rebuilt.get_mut(&target_index) else {
                file_offset += part.size as u64;
                continue;
            };
            if file_offset.saturating_add(part.size as u64) > file.size {
                chunk.valid = false;
                file_offset += part.size as u64;
                continue;
            }
            let mut data = vec![0; part.size as usize];
            if source.seek(SeekFrom::Start(file_offset)).is_err()
                || source.read_exact(&mut data).is_err()
            {
                chunk.valid = false;
                file_offset += part.size as u64;
                continue;
            }
            for (n, byte) in data.into_iter().enumerate() {
                let at = part.offset as usize + n;
                if chunk.covered[at] && chunk.bytes[at] != byte {
                    chunk.valid = false;
                    break;
                }
                chunk.bytes[at] = byte;
                chunk.covered[at] = true;
            }
            file_offset += part.size as u64;
        }
    }

    let mut seeded = Vec::new();
    let mut bytes_written = 0u64;
    for (index, chunk) in rebuilt {
        if cancel.load(Ordering::Relaxed) {
            break;
        }
        if !chunk.valid || chunk.covered.iter().any(|present| !present) {
            continue;
        }
        if target.chunks[index as usize]
            .sha1
            .is_some_and(|expected| <[u8; 20]>::from(Sha1::digest(&chunk.bytes)) != expected)
        {
            continue;
        }
        if store(
            dir,
            &guid_text(&target.chunks[index as usize].guid),
            &chunk.bytes,
        )
        .is_ok()
        {
            bytes_written += chunk.bytes.len() as u64;
            seeded.push(index);
        }
    }
    (seeded, bytes_written)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::epic::manifest::{
        testing::{build, chunk, TestFile, TestManifest},
        PartRef,
    };
    use sha1::Digest;

    #[test]
    fn keeps_only_valid_needed_entries_within_budget() {
        let dir = std::env::temp_dir().join(format!("dd-epic-cache-{}", std::process::id()));
        let _ = fs::remove_dir_all(&dir);
        fs::create_dir_all(dir.join("subdir")).unwrap();
        let mut chunks = vec![chunk(1, 4), chunk(2, 4), chunk(3, 4), chunk(4, 4)];
        chunks[1].sha1 = Some(Sha1::digest(b"good").into());
        chunks[2].sha1 = Some(Sha1::digest(b"good").into());
        store(&dir, &entry_name(&chunks[0], 0), b"aaaa").unwrap();
        store(&dir, &entry_name(&chunks[1], 0), b"good").unwrap();
        store(&dir, &entry_name(&chunks[2], 0), b"bad!").unwrap();
        store(&dir, &entry_name(&chunks[3], 0), b"dddd").unwrap();
        store(&dir, &entry_name(&chunks[0], 2), b"aaaa").unwrap();
        fs::write(dir.join("junk.part"), b"x").unwrap();
        fs::write(dir.join(entry_name(&chunk(9, 4), 0)), b"zzzz").unwrap();
        let needed: HashMap<u32, u32> = [(0, 5), (1, 1), (2, 2), (3, 9)].into_iter().collect();
        let cancel = AtomicBool::new(false);
        let kept = prepare(&dir, &chunks, &needed, 8, &cancel);
        assert_eq!(kept, vec![1, 0]);
        let mut left: Vec<String> = fs::read_dir(&dir).unwrap().flatten().map(|e| e.file_name().to_string_lossy().into_owned()).collect();
        left.sort();
        let mut want = vec![guid_text(&chunks[0].guid), guid_text(&chunks[1].guid)];
        want.sort();
        assert_eq!(left, want);
        let _ = fs::remove_dir_all(&dir);
    }

    #[test]
    fn reconstructs_needed_chunks_only_from_sha1_verified_installed_files() {
        let root =
            std::env::temp_dir().join(format!("dd-epic-installed-chunks-{}", std::process::id()));
        let game = root.join("game");
        let cache = root.join("cache");
        let _ = fs::remove_dir_all(&root);
        fs::create_dir_all(&game).unwrap();
        let info = chunk(41, 4);
        let make = || TestManifest {
            version: 21,
            build: "installed".into(),
            exe: "Game.exe".into(),
            prereq: None,
            chunks: vec![info.clone()],
            files: vec![TestFile {
                path: "source.bin".into(),
                sha1: Sha1::digest(b"abcd").into(),
                tags: Vec::new(),
                parts: vec![PartRef {
                    guid: info.guid,
                    offset: 0,
                    size: 4,
                }],
            }],
            compress: false,
            meta_tail: Vec::new(),
        };
        let installed = crate::epic::manifest::parse(&build(&make())).unwrap();
        let target = crate::epic::manifest::parse(&build(&make())).unwrap();
        fs::write(game.join("source.bin"), b"abcd").unwrap();
        let cancel = AtomicBool::new(false);
        let needed = HashSet::from([0]);
        let (seeded, written) = seed_from_verified_installed(
            &cache,
            &game,
            &installed,
            &target,
            &needed,
            &[],
            4,
            &cancel,
        );
        assert_eq!(seeded, vec![0]);
        assert_eq!(written, 4);
        assert_eq!(
            fs::read(cache.join(guid_text(&info.guid))).unwrap(),
            b"abcd"
        );

        fs::remove_dir_all(&cache).unwrap();
        fs::write(game.join("source.bin"), b"abXd").unwrap();
        let (seeded, written) = seed_from_verified_installed(
            &cache,
            &game,
            &installed,
            &target,
            &needed,
            &[],
            4,
            &cancel,
        );
        assert!(seeded.is_empty());
        assert_eq!(written, 0);
        assert!(!cache.join(guid_text(&info.guid)).exists());
        let _ = fs::remove_dir_all(&root);
    }
}
