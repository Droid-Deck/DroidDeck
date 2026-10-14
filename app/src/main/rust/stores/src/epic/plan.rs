
use std::collections::{HashMap, HashSet};
use std::path::{Path, PathBuf};

use super::manifest::{ChunkInfo, Manifest};

pub fn required_install_files(m: &Manifest) -> Vec<usize> {
    let required: Vec<usize> = m
        .files
        .iter()
        .enumerate()
        .filter(|(_, f)| f.install_tags.is_empty())
        .map(|(i, _)| i)
        .collect();
    if required.is_empty() {
        (0..m.files.len()).collect()
    } else {
        required
    }
}

pub fn resolve_install_files(m: &Manifest, selected_tags: Option<&[String]>) -> Vec<usize> {
    let Some(tags) = selected_tags else {
        return (0..m.files.len()).collect();
    };
    if tags.is_empty() {
        return required_install_files(m);
    }
    let want: HashSet<&str> = tags.iter().map(String::as_str).collect();
    let mut out = Vec::new();
    for (i, f) in m.files.iter().enumerate() {
        if f.install_tags.is_empty() {
            out.push(i);
            continue;
        }
        if f.install_tags.iter().any(|t| want.contains(t.as_str())) {
            out.push(i);
        }
    }
    if out.is_empty() {
        required_install_files(m)
    } else {
        out
    }
}

pub fn unique_chunks_for_files(m: &Manifest, file_indices: &[usize]) -> Vec<usize> {
    let by_guid: HashMap<String, usize> = m.chunk_index_by_guid();
    let mut seen: HashSet<String> = HashSet::new();
    let mut chunks = Vec::new();
    for &fi in file_indices {
        let Some(f) = m.files.get(fi) else {
            continue;
        };
        for p in &f.parts {
            let g = p.guid_str();
            if seen.insert(g.clone()) {
                if let Some(&ci) = by_guid.get(&g) {
                    chunks.push(ci);
                }
            }
        }
    }
    chunks
}

pub fn total_credit_bytes(m: &Manifest, chunk_indices: &[usize]) -> u64 {
    chunk_indices
        .iter()
        .filter_map(|&i| m.unique_chunks.get(i))
        .map(ChunkInfo::credit_bytes)
        .sum()
}

pub fn chunk_cache_dir(install_dir: &str) -> PathBuf {
    Path::new(install_dir).join(super::CHUNK_CACHE_DIR)
}

pub fn resolve_cache_dir(install_dir: &str, cache_dir: &str) -> PathBuf {
    if cache_dir.is_empty() {
        chunk_cache_dir(install_dir)
    } else {
        PathBuf::from(cache_dir)
    }
}

pub fn cached_chunk_path(cache_dir: &Path, chunk: &ChunkInfo) -> PathBuf {
    cache_dir.join(chunk.guid_str())
}

pub fn is_chunk_cached(cache_dir: &Path, chunk: &ChunkInfo) -> bool {
    cached_chunk_path(cache_dir, chunk).exists()
}

pub fn chunk_url(cdn_prefix: &str, chunk_dir: &str, chunk: &ChunkInfo) -> String {
    format!("{}/{}", cdn_prefix, chunk.path(chunk_dir))
}

pub fn normalize_prefix(raw: &str) -> String {
    let unescaped = raw.replace("\\/", "/").replace('\\', "");
    unescaped.trim_end_matches('/').to_string()
}

pub fn distinct_prefixes(cdn_prefixes: &[String]) -> Vec<String> {
    let mut seen = HashSet::new();
    let mut out = Vec::new();
    for p in cdn_prefixes {
        let n = normalize_prefix(p);
        if n.is_empty() {
            continue;
        }
        if seen.insert(n.clone()) {
            out.push(n);
        }
    }
    out
}

pub fn per_host_cap(max_workers: usize, host_count: usize) -> usize {
    let hosts = host_count.max(1);
    let workers = max_workers.max(1);
    let split = (workers + hosts - 1) / hosts;
    split.max(6)
}

#[cfg(test)]
mod tests {
    use super::super::manifest::test_support::*;
    use super::super::manifest::parse_manifest;
    use super::*;

    fn manifest() -> Manifest {
        parse_manifest(&build_manifest(&sample_chunks(), &sample_files(), 21, false)).unwrap()
    }

    #[test]
    fn install_tag_rules_match_java() {
        let m = manifest();
        assert_eq!(resolve_install_files(&m, None), vec![0, 1, 2]);
        assert_eq!(resolve_install_files(&m, Some(&[])), vec![0]);
        let de = vec!["German".to_string(), "de-DE".to_string()];
        assert_eq!(resolve_install_files(&m, Some(&de)), vec![0, 1]);
        let xx = vec!["Klingon".to_string()];
        assert_eq!(resolve_install_files(&m, Some(&xx)), vec![0]);
    }

    #[test]
    fn manifest_without_tags_treats_every_file_as_required() {
        let mut files = sample_files();
        for f in files.iter_mut() {
            f.tags.clear();
        }
        let m = parse_manifest(&build_manifest(&sample_chunks(), &files, 21, false)).unwrap();
        assert_eq!(required_install_files(&m), vec![0, 1, 2]);
        assert_eq!(resolve_install_files(&m, Some(&[])), vec![0, 1, 2]);
    }

    #[test]
    fn manifest_where_every_file_is_tagged_falls_back_to_all() {
        let mut files = sample_files();
        files[0].tags = vec!["English".to_string()];
        let m = parse_manifest(&build_manifest(&sample_chunks(), &files, 21, false)).unwrap();
        let xx = vec!["Klingon".to_string()];
        assert_eq!(resolve_install_files(&m, Some(&xx)), vec![0, 1, 2]);
    }

    #[test]
    fn unique_chunks_follow_first_seen_part_order() {
        let m = manifest();
        assert_eq!(unique_chunks_for_files(&m, &[2, 0]), vec![2, 0, 1]);
        assert_eq!(unique_chunks_for_files(&m, &[1]), vec![1]);
        assert!(unique_chunks_for_files(&m, &[]).is_empty());
        assert!(unique_chunks_for_files(&m, &[99]).is_empty(), "out-of-range index ignored");
        assert_eq!(total_credit_bytes(&m, &[0, 1, 2]), 700_000 + 1 + 9);
    }

    #[test]
    fn parts_referencing_unknown_guids_are_dropped() {
        let mut files = sample_files();
        files[0]
            .parts
            .push(([0x1234, 0x5678, 0x9ABC, 0xDEF0], 0, 1));
        let m = parse_manifest(&build_manifest(&sample_chunks(), &files, 21, false)).unwrap();
        assert_eq!(unique_chunks_for_files(&m, &[0]), vec![0, 1]);
    }

    #[test]
    fn urls_and_cache_paths_match_java() {
        let m = manifest();
        let c = &m.unique_chunks[0];
        assert_eq!(
            chunk_url("https://fastly-download.epicgames.com/Builds/Org/o-x/y/default", "ChunksV4", c),
            "https://fastly-download.epicgames.com/Builds/Org/o-x/y/default/ChunksV4/05/0123456789ABCDEF_00000001000000020000000300000004.chunk"
        );
        let cache = chunk_cache_dir("/data/x/imagefs/epic_games/Game");
        assert_eq!(cache, PathBuf::from("/data/x/imagefs/epic_games/Game/.chunks"));
        assert_eq!(resolve_cache_dir("/data/x/imagefs/epic_games/Game", ""), cache);
        assert_eq!(
            resolve_cache_dir("/sdcard/Games/Game", "/data/x/cache/epic/Game"),
            PathBuf::from("/data/x/cache/epic/Game")
        );
        assert_eq!(
            cached_chunk_path(&cache, c),
            PathBuf::from("/data/x/imagefs/epic_games/Game/.chunks/00000001000000020000000300000004")
        );
        let prefixes = vec!["a".to_string(), "b".to_string(), "a".to_string()];
        assert_eq!(distinct_prefixes(&prefixes), vec!["a".to_string(), "b".to_string()]);
    }

    #[test]
    fn prefixes_are_unescaped_from_the_java_json_scan() {
        let raw = "https:\\/\\/egdownload.fastly-edge.com\\/Builds\\/Org\\/o-83e8\\/04b7\\/default\\";
        assert_eq!(
            normalize_prefix(raw),
            "https://egdownload.fastly-edge.com/Builds/Org/o-83e8/04b7/default"
        );
        assert_eq!(
            normalize_prefix("https://download.epicgames.com/Builds/x/default/"),
            "https://download.epicgames.com/Builds/x/default"
        );
        let m = manifest();
        let c = &m.unique_chunks[0];
        assert_eq!(
            chunk_url(&normalize_prefix(raw), "ChunksV4", c),
            "https://egdownload.fastly-edge.com/Builds/Org/o-83e8/04b7/default/ChunksV4/05/0123456789ABCDEF_00000001000000020000000300000004.chunk"
        );
        let two = vec![raw.to_string(), "https://egdownload.fastly-edge.com/Builds/Org/o-83e8/04b7/default".to_string(), String::new()];
        assert_eq!(distinct_prefixes(&two).len(), 1, "escaped and clean forms collapse; empty dropped");
    }

    #[test]
    fn per_host_cap_splits_the_window_with_a_floor_of_six() {
        assert_eq!(per_host_cap(32, 3), 11);
        assert_eq!(per_host_cap(32, 1), 32);
        assert_eq!(per_host_cap(32, 2), 16);
        assert_eq!(per_host_cap(8, 3), 6, "floor");
        assert_eq!(per_host_cap(8, 1), 8);
        assert_eq!(per_host_cap(0, 0), 6, "degenerate inputs");
    }
}
