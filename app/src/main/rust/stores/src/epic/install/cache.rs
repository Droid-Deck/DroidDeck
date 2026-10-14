use std::collections::HashMap;
use std::fs;
use std::io::{Read, Write};
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, Ordering};

use sha1::{Digest, Sha1};

use crate::epic::manifest::{guid_from_hex, guid_text, ChunkInfo};

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
        let Ok(meta) = fs::symlink_metadata(&path) else { continue };
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

#[cfg(test)]
mod tests {
    use super::*;
    use crate::epic::manifest::testing::chunk;

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
}
