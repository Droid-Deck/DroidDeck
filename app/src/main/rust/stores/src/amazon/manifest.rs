use std::collections::HashMap;

use super::codec;
use super::net::hex_lower;
use super::proto::{Fields, Value};

pub const SHA256_ALGORITHM: u64 = 0;

pub const RESERVED_ROOT_NAMES: &[&str] = &[
    ".droiddeck-store.json",
    ".droiddeck-store.json.tmp",
    ".droiddeck-launch.bat",
    ".amazon_download_in_progress",
    ".amazon_download_complete",
];

pub const TEMP_SUFFIX: &str = ".tmp";

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct GameFile {
    pub path: String,
    pub size: u64,
    pub algorithm: u64,
    pub digest: Vec<u8>,
}

impl GameFile {
    pub fn sha256(&self) -> Option<[u8; 32]> {
        if self.algorithm == SHA256_ALGORITHM {
            self.digest.as_slice().try_into().ok()
        } else {
            None
        }
    }

    pub fn cdn_name(&self) -> String {
        hex_lower(&self.digest)
    }
}

#[derive(Debug, Default)]
pub struct Manifest {
    pub files: Vec<GameFile>,
    pub entries: usize,
    pub warnings: Vec<String>,
}

impl Manifest {
    pub fn total_size(&self) -> u64 {
        self.files.iter().map(|f| f.size).sum()
    }
}

pub fn check_path(raw: &str) -> Result<String, String> {
    let path = raw.replace('\\', "/");
    let refuse = |why: &str| Err(format!("The manifest has an unsafe path ({why}): {raw}"));
    if path.starts_with('/') {
        return refuse("absolute");
    }
    if path.contains(':') {
        return refuse("drive letter or stream name");
    }
    if path.contains('\0') {
        return refuse("NUL character");
    }
    for segment in path.split('/') {
        match segment {
            "" => return refuse("empty segment"),
            "." | ".." => return refuse("relative segment"),
            _ => {}
        }
    }
    if !path.contains('/') && RESERVED_ROOT_NAMES.iter().any(|r| r.eq_ignore_ascii_case(&path)) {
        return refuse("DroidDeck's own file");
    }
    Ok(path)
}

fn parse_hash(data: &[u8]) -> Result<(u64, Vec<u8>), String> {
    let mut algorithm = SHA256_ALGORITHM;
    let mut digest = Vec::new();
    let mut fields = Fields::new(data);
    while let Some((n, v)) = fields.next_field()? {
        match (n, v) {
            (1, Value::Varint(a)) => algorithm = a,
            (2, Value::Bytes(b)) => digest = b.to_vec(),
            (1 | 2, _) => return Err(format!("hash field {n} has the wrong type")),
            _ => {}
        }
    }
    Ok((algorithm, digest))
}

fn parse_file(data: &[u8]) -> Result<Option<GameFile>, String> {
    let mut path = String::new();
    let mut size = 0u64;
    let mut hash = (SHA256_ALGORITHM, Vec::new());
    let mut fields = Fields::new(data);
    while let Some((n, v)) = fields.next_field()? {
        match (n, v) {
            (1, Value::Bytes(b)) => {
                path = String::from_utf8(b.to_vec()).map_err(|_| "a file path is not UTF-8".to_string())?
            }
            (3, Value::Varint(s)) => size = s,
            (5, Value::Bytes(b)) => hash = parse_hash(b)?,
            (1 | 3 | 5, _) => return Err(format!("file field {n} has the wrong type")),
            _ => {}
        }
    }
    if path.is_empty() {
        return Ok(None);
    }
    let path = check_path(&path)?;
    let (algorithm, digest) = hash;
    if size > 0 && digest.is_empty() {
        return Err(format!("The manifest gives no hash for {path}"));
    }
    if algorithm == SHA256_ALGORITHM && !digest.is_empty() && digest.len() != 32 {
        return Err(format!("The manifest has a {}-byte SHA-256 for {path}", digest.len()));
    }
    Ok(Some(GameFile { path, size, algorithm, digest }))
}

pub fn parse_message(data: &[u8]) -> Result<Manifest, String> {
    let mut out = Manifest::default();
    let mut index: HashMap<String, usize> = HashMap::new();
    let mut top = Fields::new(data);
    while let Some((n, v)) = top.next_field()? {
        let package = match (n, v) {
            (1, Value::Bytes(b)) => b,
            (1, _) => return Err("package field has the wrong type".into()),
            _ => continue,
        };
        let mut fields = Fields::new(package);
        while let Some((n, v)) = fields.next_field()? {
            let entry = match (n, v) {
                (2, Value::Bytes(b)) => b,
                (2, _) => return Err("file field has the wrong type".into()),
                _ => continue,
            };
            let Some(file) = parse_file(entry)? else { continue };
            out.entries += 1;
            match index.get(&file.path) {
                Some(&i) => {
                    out.warnings.push(format!("duplicate manifest path {}; the later entry wins", file.path));
                    out.files[i] = file;
                }
                None => {
                    index.insert(file.path.clone(), out.files.len());
                    out.files.push(file);
                }
            }
        }
    }
    for f in &out.files {
        if let Some(base) = f.path.strip_suffix(TEMP_SUFFIX) {
            if index.contains_key(base) {
                return Err(format!("The manifest has a file that clashes with a temporary file: {}", f.path));
            }
        }
    }
    Ok(out)
}

pub fn parse(object: &[u8]) -> Result<Manifest, String> {
    parse_message(&codec::message(object)?)
}

#[cfg(test)]
pub mod testing {
    use crate::amazon::proto::write;

    pub struct Entry<'a> {
        pub path: &'a str,
        pub size: u64,
        pub algorithm: Option<u64>,
        pub digest: &'a [u8],
    }

    pub fn file_message(e: &Entry<'_>) -> Vec<u8> {
        let mut hash = Vec::new();
        if let Some(a) = e.algorithm {
            write::uint(&mut hash, 1, a);
        }
        write::bytes(&mut hash, 2, e.digest);
        let mut f = Vec::new();
        write::bytes(&mut f, 1, e.path.as_bytes());
        write::uint(&mut f, 2, 1_700_000_000);
        write::uint(&mut f, 3, e.size);
        write::bytes(&mut f, 5, &hash);
        f
    }

    pub fn message(packages: &[Vec<Entry<'_>>]) -> Vec<u8> {
        let mut m = Vec::new();
        write::uint(&mut m, 7, 3);
        for files in packages {
            let mut p = Vec::new();
            write::bytes(&mut p, 1, b"package-name");
            for e in files {
                write::bytes(&mut p, 2, &file_message(e));
            }
            write::bytes(&mut m, 1, &p);
        }
        m
    }
}

#[cfg(test)]
mod tests {
    use super::testing::{message, Entry};
    use super::*;
    use crate::amazon::codec::testing::frame;
    use sha2::{Digest, Sha256};

    #[test]
    fn parses_files_across_packages() {
        let d1: Vec<u8> = Sha256::digest(b"one").to_vec();
        let d2: Vec<u8> = Sha256::digest(b"two").to_vec();
        let msg = message(&[
            vec![
                Entry { path: "Binaries\\Win64\\Game.exe", size: 3, algorithm: Some(0), digest: &d1 },
                Entry { path: "", size: 9, algorithm: None, digest: &d1 },
            ],
            vec![Entry { path: "fuel.json", size: 3, algorithm: None, digest: &d2 }],
        ]);
        for xz in [true, false] {
            let m = parse(&frame(&msg, b"\x0a\x00", xz)).unwrap();
            assert_eq!(m.files.len(), 2);
            assert_eq!(m.entries, 2);
            assert_eq!(m.files[0].path, "Binaries/Win64/Game.exe");
            assert_eq!(m.files[0].sha256().unwrap().to_vec(), d1);
            assert_eq!(m.files[0].cdn_name(), hex_lower(&d1));
            assert_eq!(m.files[1].algorithm, 0, "absent algorithm means SHA-256");
            assert_eq!(m.total_size(), 6);
        }
    }

    #[test]
    fn other_algorithms_are_not_verified_but_still_named() {
        let msg = message(&[vec![Entry { path: "a", size: 1, algorithm: Some(7), digest: &[0xab, 0xcd] }]]);
        let m = parse_message(&msg).unwrap();
        assert_eq!(m.files[0].sha256(), None);
        assert_eq!(m.files[0].cdn_name(), "abcd");
    }

    #[test]
    fn duplicate_paths_keep_the_later_entry() {
        let a = [1u8; 32];
        let b = [2u8; 32];
        let msg = message(&[vec![
            Entry { path: "x\\y.bin", size: 1, algorithm: Some(0), digest: &a },
            Entry { path: "x/y.bin", size: 2, algorithm: Some(0), digest: &b },
        ]]);
        let m = parse_message(&msg).unwrap();
        assert_eq!(m.files.len(), 1);
        assert_eq!(m.entries, 2);
        assert_eq!(m.files[0].size, 2);
        assert_eq!(m.warnings.len(), 1);
    }

    #[test]
    fn unsafe_paths_are_refused() {
        for bad in [
            "\\abs.exe",
            "/abs.exe",
            "C:\\Windows\\x.dll",
            "a\\..\\..\\b",
            "..",
            "a\\\\b",
            "a\\",
            "a\\.\\b",
            ".droiddeck-store.json",
            ".DroidDeck-Launch.BAT",
            ".amazon_download_complete",
        ] {
            assert!(check_path(bad).is_err(), "{bad}");
            let msg = message(&[vec![Entry { path: bad, size: 1, algorithm: Some(0), digest: &[0; 32] }]]);
            let err = parse_message(&msg).unwrap_err();
            assert!(err.contains(bad), "{err}");
        }
        assert_eq!(check_path("sub\\.droiddeck-store.json").unwrap(), "sub/.droiddeck-store.json");
        assert_eq!(check_path("..hidden\\x..y").unwrap(), "..hidden/x..y");
    }

    #[test]
    fn malformed_entries_are_errors() {
        let no_hash = message(&[vec![Entry { path: "a", size: 5, algorithm: Some(0), digest: &[] }]]);
        assert!(parse_message(&no_hash).is_err());
        let empty_ok = message(&[vec![Entry { path: "e", size: 0, algorithm: Some(0), digest: &[] }]]);
        assert_eq!(parse_message(&empty_ok).unwrap().files[0].sha256(), None);
        let short = message(&[vec![Entry { path: "a", size: 5, algorithm: Some(0), digest: &[1, 2] }]]);
        assert!(parse_message(&short).is_err());
        let clash = message(&[vec![
            Entry { path: "a.bin", size: 1, algorithm: Some(0), digest: &[1; 32] },
            Entry { path: "a.bin.tmp", size: 1, algorithm: Some(0), digest: &[2; 32] },
        ]]);
        assert!(parse_message(&clash).is_err());
        let mut truncated = message(&[vec![Entry { path: "a", size: 1, algorithm: Some(0), digest: &[1; 32] }]]);
        truncated.truncate(truncated.len() - 3);
        assert!(parse_message(&truncated).is_err());
        assert!(parse_message(&[0x08, 0x01]).is_err());
    }
}
