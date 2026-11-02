use std::collections::HashMap;
use std::fs;
use std::io::Write;
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicU64, Ordering};

use sha1::{Digest, Sha1};

use crate::epic::manifest::Sha1 as Hash;

pub const DIR_NAME: &str = ".droiddeck-epic-staging";
pub const OWNER_FILE: &str = "owner";
pub const OWNER_MARKER: &[u8] = b"DroidDeck Epic staging v1\n";
const SUFFIX: &str = ".stage";
pub const PARTIAL_SUFFIX: &str = ".part";
const LIST_SUFFIX: &str = ".part.list";

static SEQUENCE: AtomicU64 = AtomicU64::new(0);

pub fn conflicts(manifest_path: &str) -> bool {
    let first = manifest_path.trim_start_matches('/').split('/').next().unwrap_or("");
    first.eq_ignore_ascii_case(DIR_NAME)
}

fn hex(bytes: &[u8]) -> String {
    bytes.iter().map(|b| format!("{b:02x}")).collect()
}

pub fn partial_name(path: &str, size: u64, sha1: Option<Hash>) -> String {
    let id: Hash = Sha1::digest(path.as_bytes()).into();
    let content = sha1.map(|h| hex(&h)).unwrap_or_else(|| "0".into());
    format!("{}-{content}-{size}{PARTIAL_SUFFIX}", hex(&id[..8]))
}

pub fn list_path(partial: &Path) -> PathBuf {
    let mut name = partial.as_os_str().to_owned();
    name.push(".list");
    PathBuf::from(name)
}

pub fn remove_partial(partial: &Path) {
    let _ = fs::remove_file(partial);
    let _ = fs::remove_file(list_path(partial));
}

pub fn partials(dir: &Path) -> HashMap<String, PathBuf> {
    let Ok(entries) = fs::read_dir(dir) else { return HashMap::new() };
    entries
        .flatten()
        .map(|e| e.file_name().to_string_lossy().into_owned())
        .filter(|n| n.ends_with(PARTIAL_SUFFIX))
        .map(|n| (n.clone(), dir.join(n)))
        .collect()
}

pub fn unique_name() -> String {
    let nanos = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.subsec_nanos())
        .unwrap_or(0);
    format!(
        "epic-{}-{}-{nanos}{SUFFIX}",
        std::process::id(),
        SEQUENCE.fetch_add(1, Ordering::Relaxed)
    )
}

fn write_marker(path: &Path) -> Result<(), String> {
    let mut f = fs::File::create(path).map_err(|e| format!("staging marker: {e}"))?;
    f.write_all(OWNER_MARKER).map_err(|e| format!("staging marker: {e}"))?;
    f.sync_all().map_err(|e| format!("staging marker: {e}"))?;
    Ok(())
}

pub fn recover(game_dir: &Path) -> Result<PathBuf, String> {
    fs::create_dir_all(game_dir).map_err(|e| format!("game folder: {e}"))?;
    let game = fs::canonicalize(game_dir).map_err(|e| format!("game folder: {e}"))?;
    let expected = game.join(DIR_NAME);
    let dir = game_dir.join(DIR_NAME);
    let refuse = |why: &str| Err(format!("The staging folder {DIR_NAME} is not usable ({why}); nothing was changed"));

    match fs::symlink_metadata(&dir) {
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => {
            fs::create_dir(&dir).map_err(|e| format!("staging folder: {e}"))?;
            write_marker(&expected.join(OWNER_FILE))?;
            return Ok(expected);
        }
        Err(e) => return Err(format!("staging folder: {e}")),
        Ok(meta) => {
            if meta.file_type().is_symlink() || !meta.is_dir() {
                return refuse("not a plain directory");
            }
        }
    }
    let canonical = fs::canonicalize(&dir).map_err(|e| format!("staging folder: {e}"))?;
    if canonical != expected {
        return refuse("it resolves elsewhere");
    }

    let mut stale = Vec::new();
    let mut owner_ok = false;
    let mut others = 0usize;
    let entries = fs::read_dir(&canonical).map_err(|e| format!("staging folder: {e}"))?;
    for entry in entries {
        let entry = entry.map_err(|e| format!("staging folder: {e}"))?;
        let name = entry.file_name().to_string_lossy().into_owned();
        let path = canonical.join(&name);
        let meta = fs::symlink_metadata(&path).map_err(|e| format!("staging folder: {e}"))?;
        if meta.file_type().is_symlink() || !meta.is_file() {
            return refuse("it holds something that is not a plain file");
        }
        if fs::canonicalize(&path).ok().as_deref().and_then(Path::parent) != Some(canonical.as_path()) {
            return refuse("an entry resolves elsewhere");
        }
        if name == OWNER_FILE {
            match fs::read(&path) {
                Ok(content) if content == OWNER_MARKER => owner_ok = true,
                _ => return refuse("its owner marker is not ours"),
            }
        } else if name.ends_with(SUFFIX) {
            stale.push(path);
            others += 1;
        } else if name.ends_with(PARTIAL_SUFFIX) || name.ends_with(LIST_SUFFIX) {
            others += 1;
        } else {
            return refuse("it holds an unexpected file");
        }
    }
    if !owner_ok {
        if others == 0 {
            write_marker(&canonical.join(OWNER_FILE))?;
            return Ok(canonical);
        }
        return refuse("its owner marker is missing");
    }
    for path in stale {
        fs::remove_file(&path).map_err(|e| format!("removing an old stage file: {e}"))?;
    }
    Ok(canonical)
}

pub fn remove_if_idle(dir: &Path) {
    let Ok(entries) = fs::read_dir(dir) else { return };
    let names: Vec<String> = entries.flatten().map(|e| e.file_name().to_string_lossy().into_owned()).collect();
    if names.iter().all(|n| n == OWNER_FILE) {
        let marker = dir.join(OWNER_FILE);
        if names.is_empty() || fs::read(&marker).map(|c| c == OWNER_MARKER).unwrap_or(false) {
            let _ = fs::remove_file(&marker);
            let _ = fs::remove_dir(dir);
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn temp(name: &str) -> PathBuf {
        let dir = std::env::temp_dir().join(format!("dd-epic-staging-{name}-{}", std::process::id()));
        let _ = fs::remove_dir_all(&dir);
        fs::create_dir_all(&dir).unwrap();
        dir
    }

    #[test]
    fn creates_validates_and_cleans() {
        let game = temp("ok");
        let dir = recover(&game).unwrap();
        assert_eq!(fs::read(dir.join(OWNER_FILE)).unwrap(), OWNER_MARKER);
        fs::write(dir.join("file-1.stage"), b"x").unwrap();
        fs::write(dir.join("stream-1-2-3.stage"), b"y").unwrap();
        fs::write(game.join("keep.stage"), b"z").unwrap();
        let partial = partial_name("Game/a.pak", 10, None);
        fs::write(dir.join(&partial), b"p").unwrap();
        fs::write(list_path(&dir.join(&partial)), b"0\n").unwrap();
        recover(&game).unwrap();
        assert_eq!(fs::read_dir(&dir).unwrap().count(), 3);
        assert_eq!(partials(&dir).into_keys().collect::<Vec<_>>(), vec![partial.clone()]);
        remove_partial(&dir.join(&partial));
        assert!(game.join("keep.stage").exists());
        remove_if_idle(&dir);
        assert!(!dir.exists());
        let _ = fs::remove_dir_all(&game);
    }

    #[test]
    fn foreign_contents_are_left_alone() {
        let game = temp("foreign");
        let dir = game.join(DIR_NAME);
        fs::create_dir_all(&dir).unwrap();
        fs::write(dir.join(OWNER_FILE), b"someone else\n").unwrap();
        fs::write(dir.join("a.stage"), b"x").unwrap();
        assert!(recover(&game).is_err());
        assert!(dir.join("a.stage").exists());
        fs::write(dir.join(OWNER_FILE), OWNER_MARKER).unwrap();
        fs::write(dir.join("notes.txt"), b"x").unwrap();
        assert!(recover(&game).is_err());
        assert!(dir.join("a.stage").exists());
        let _ = fs::remove_dir_all(&game);
    }

    #[test]
    fn a_file_in_place_of_the_folder_is_refused() {
        let game = temp("file");
        fs::write(game.join(DIR_NAME), b"x").unwrap();
        assert!(recover(&game).is_err());
        let _ = fs::remove_dir_all(&game);
    }

    #[test]
    fn manifest_conflicts_and_names() {
        assert!(conflicts(".droiddeck-epic-staging/x"));
        assert!(conflicts(".DroidDeck-Epic-Staging"));
        assert!(!conflicts("Game/.droiddeck-epic-staging"));
        let (a, b) = (unique_name(), unique_name());
        assert_ne!(a, b);
        assert!(a.ends_with(".stage"));
        let p = partial_name("Game/a.pak", 10, Some([0xab; 20]));
        assert!(p.ends_with(&format!("-{}-10.part", "ab".repeat(20))));
        assert_ne!(p, partial_name("Game/b.pak", 10, Some([0xab; 20])));
        assert_ne!(p, partial_name("Game/a.pak", 10, Some([0xac; 20])));
    }
}
