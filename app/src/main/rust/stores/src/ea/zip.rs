// SPDX-License-Identifier: GPL-3.0-or-later
//! The central directory of a remote ZIP archive, which is how EA publishes a game build.

const EOCD: u32 = 0x0605_4b50;
const EOCD64: u32 = 0x0606_4b50;
const EOCD64_LOCATOR: u32 = 0x0706_4b50;
const CENTRAL: u32 = 0x0201_4b50;
const LOCAL: u32 = 0x0403_4b50;
const EOCD_LEN: usize = 22;
const LOCATOR_LEN: usize = 20;
const EOCD64_LEN: usize = 56;
pub const LOCAL_FIXED: usize = 30;
pub const TAIL_LEN: u64 = 128 * 1024;

pub const STORED: u16 = 0;
pub const DEFLATED: u16 = 8;

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Entry {
    pub name: String,
    pub crc32: u32,
    pub method: u16,
    pub compressed: u64,
    pub size: u64,
    pub local_offset: u64,
}

impl Entry {
    pub fn is_dir(&self) -> bool {
        self.name.ends_with('/') || self.name.ends_with('\\')
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Directory {
    pub offset: u64,
    pub size: u64,
    pub entries: u64,
}

fn u16_at(b: &[u8], at: usize) -> Option<u16> {
    b.get(at..at + 2).map(|s| u16::from_le_bytes([s[0], s[1]]))
}

fn u32_at(b: &[u8], at: usize) -> Option<u32> {
    b.get(at..at + 4).map(|s| u32::from_le_bytes([s[0], s[1], s[2], s[3]]))
}

fn u64_at(b: &[u8], at: usize) -> Option<u64> {
    b.get(at..at + 8).map(|s| u64::from_le_bytes(s.try_into().unwrap_or([0; 8])))
}

/// Finds the central directory from the last bytes of the archive. `tail_start` is where `tail`
/// begins in the whole file.
pub fn locate(tail: &[u8], tail_start: u64) -> Result<Directory, String> {
    if tail.len() < EOCD_LEN {
        return Err("The archive is too short to be a ZIP".into());
    }
    let mut at = tail.len() - EOCD_LEN;
    let found = loop {
        if u32_at(tail, at) == Some(EOCD) {
            let comment = u16_at(tail, at + 20).unwrap_or(0) as usize;
            if at + EOCD_LEN + comment <= tail.len() {
                break at;
            }
        }
        if at == 0 {
            return Err("The archive has no end of central directory".into());
        }
        at -= 1;
    };
    let entries = u16_at(tail, found + 10).unwrap_or(0) as u64;
    let size = u32_at(tail, found + 12).unwrap_or(0) as u64;
    let offset = u32_at(tail, found + 16).unwrap_or(0) as u64;
    let wide = entries == 0xFFFF || size == 0xFFFF_FFFF || offset == 0xFFFF_FFFF;
    if !wide {
        return Ok(Directory { offset, size, entries });
    }
    let locator = found.checked_sub(LOCATOR_LEN).filter(|&l| u32_at(tail, l) == Some(EOCD64_LOCATOR));
    let Some(locator) = locator else {
        return Err("The archive needs ZIP64 but has no ZIP64 locator".into());
    };
    let record_at = u64_at(tail, locator + 8).unwrap_or(0);
    let Some(rel) = record_at.checked_sub(tail_start).map(|r| r as usize) else {
        return Err("The ZIP64 directory record lies before the fetched tail".into());
    };
    if u32_at(tail, rel) != Some(EOCD64) || rel + EOCD64_LEN > tail.len() {
        return Err("The ZIP64 directory record is damaged".into());
    }
    Ok(Directory {
        entries: u64_at(tail, rel + 32).unwrap_or(0),
        size: u64_at(tail, rel + 40).unwrap_or(0),
        offset: u64_at(tail, rel + 48).unwrap_or(0),
    })
}

fn wide_values(extra: &[u8], need_size: bool, need_compressed: bool, need_offset: bool) -> (Option<u64>, Option<u64>, Option<u64>) {
    let mut at = 0;
    while at + 4 <= extra.len() {
        let id = u16_at(extra, at).unwrap_or(0);
        let len = u16_at(extra, at + 2).unwrap_or(0) as usize;
        let body = &extra[(at + 4).min(extra.len())..(at + 4 + len).min(extra.len())];
        if id == 0x0001 {
            let mut p = 0;
            let mut take = |want: bool| {
                if !want {
                    return None;
                }
                let v = u64_at(body, p);
                p += 8;
                v
            };
            let size = take(need_size);
            let compressed = take(need_compressed);
            let offset = take(need_offset);
            return (size, compressed, offset);
        }
        at += 4 + len;
    }
    (None, None, None)
}

pub fn parse(directory: &[u8], expected: u64) -> Result<Vec<Entry>, String> {
    let mut entries = Vec::with_capacity(expected.min(1 << 20) as usize);
    let mut at = 0usize;
    while at + 46 <= directory.len() {
        if u32_at(directory, at) != Some(CENTRAL) {
            break;
        }
        let flags = u16_at(directory, at + 8).unwrap_or(0);
        let method = u16_at(directory, at + 10).unwrap_or(0);
        let crc32 = u32_at(directory, at + 16).unwrap_or(0);
        let compressed32 = u32_at(directory, at + 20).unwrap_or(0);
        let size32 = u32_at(directory, at + 24).unwrap_or(0);
        let name_len = u16_at(directory, at + 28).unwrap_or(0) as usize;
        let extra_len = u16_at(directory, at + 30).unwrap_or(0) as usize;
        let comment_len = u16_at(directory, at + 32).unwrap_or(0) as usize;
        let offset32 = u32_at(directory, at + 42).unwrap_or(0);
        let name_at = at + 46;
        let end = name_at + name_len + extra_len + comment_len;
        if end > directory.len() {
            return Err("The archive directory is cut short".into());
        }
        let raw = &directory[name_at..name_at + name_len];
        let name = if flags & 0x0800 != 0 {
            String::from_utf8_lossy(raw).into_owned()
        } else {
            raw.iter().map(|&b| b as char).collect()
        };
        let extra = &directory[name_at + name_len..name_at + name_len + extra_len];
        let (size, compressed, offset) =
            wide_values(extra, size32 == 0xFFFF_FFFF, compressed32 == 0xFFFF_FFFF, offset32 == 0xFFFF_FFFF);
        if flags & 0x0001 != 0 {
            return Err(format!("{name} is encrypted"));
        }
        entries.push(Entry {
            name,
            crc32,
            method,
            compressed: compressed.unwrap_or(compressed32 as u64),
            size: size.unwrap_or(size32 as u64),
            local_offset: offset.unwrap_or(offset32 as u64),
        });
        at = end;
    }
    if expected != 0 && entries.len() as u64 != expected {
        return Err(format!("The archive directory lists {} of {expected} entries", entries.len()));
    }
    Ok(entries)
}

/// Bytes before the compressed data of an entry, read from its local header.
pub fn local_header_len(header: &[u8]) -> Option<Result<usize, String>> {
    if header.len() < LOCAL_FIXED {
        return None;
    }
    if u32_at(header, 0) != Some(LOCAL) {
        return Some(Err("An entry has no local header where the directory points".into()));
    }
    let name = u16_at(header, 26).unwrap_or(0) as usize;
    let extra = u16_at(header, 28).unwrap_or(0) as usize;
    Some(Ok(LOCAL_FIXED + name + extra))
}

/// A relative path inside the install folder, or None for names that would leave it.
pub fn safe_path(name: &str) -> Option<String> {
    let unified = name.replace('\\', "/");
    if unified.starts_with('/') || unified.contains(':') || unified.contains('\0') {
        return None;
    }
    let parts: Vec<&str> = unified.split('/').filter(|p| !p.is_empty() && *p != ".").collect();
    if parts.is_empty() || parts.iter().any(|p| *p == "..") {
        return None;
    }
    Some(parts.join("/"))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn central(name: &str, method: u16, crc: u32, compressed: u32, size: u32, offset: u32, extra: &[u8]) -> Vec<u8> {
        let mut v = Vec::new();
        v.extend_from_slice(&CENTRAL.to_le_bytes());
        v.extend_from_slice(&[20, 0, 20, 0, 0, 0]);
        v.extend_from_slice(&method.to_le_bytes());
        v.extend_from_slice(&[0, 0, 0, 0]);
        v.extend_from_slice(&crc.to_le_bytes());
        v.extend_from_slice(&compressed.to_le_bytes());
        v.extend_from_slice(&size.to_le_bytes());
        v.extend_from_slice(&(name.len() as u16).to_le_bytes());
        v.extend_from_slice(&(extra.len() as u16).to_le_bytes());
        v.extend_from_slice(&[0, 0, 0, 0, 0, 0, 0, 0, 0, 0]);
        v.extend_from_slice(&offset.to_le_bytes());
        v.extend_from_slice(name.as_bytes());
        v.extend_from_slice(extra);
        v
    }

    #[test]
    fn reads_a_plain_directory() {
        let mut dir = central("Game.exe", DEFLATED, 7, 10, 20, 0, &[]);
        dir.extend(central("__Installer/", STORED, 0, 0, 0, 40, &[]));
        let mut tail = vec![0u8; 5];
        tail.extend_from_slice(&dir);
        let cd_size = dir.len() as u32;
        tail.extend_from_slice(&EOCD.to_le_bytes());
        tail.extend_from_slice(&[0, 0, 0, 0, 2, 0, 2, 0]);
        tail.extend_from_slice(&cd_size.to_le_bytes());
        tail.extend_from_slice(&1000u32.to_le_bytes());
        tail.extend_from_slice(&[0, 0]);
        let found = locate(&tail, 995).unwrap();
        assert_eq!(found, Directory { offset: 1000, size: cd_size as u64, entries: 2 });
        let entries = parse(&dir, 2).unwrap();
        assert_eq!(entries[0].name, "Game.exe");
        assert_eq!(entries[0].size, 20);
        assert!(entries[1].is_dir());
    }

    #[test]
    fn reads_zip64_values() {
        let mut extra = vec![1, 0, 24, 0];
        extra.extend_from_slice(&6_000_000_000u64.to_le_bytes());
        extra.extend_from_slice(&5_000_000_000u64.to_le_bytes());
        extra.extend_from_slice(&7_000_000_000u64.to_le_bytes());
        let dir = central("big.pak", DEFLATED, 1, 0xFFFF_FFFF, 0xFFFF_FFFF, 0xFFFF_FFFF, &extra);
        let e = &parse(&dir, 1).unwrap()[0];
        assert_eq!((e.size, e.compressed, e.local_offset), (6_000_000_000, 5_000_000_000, 7_000_000_000));

        let mut tail = Vec::new();
        let record_at = 9_000_000_000u64;
        tail.extend_from_slice(&EOCD64.to_le_bytes());
        tail.extend_from_slice(&44u64.to_le_bytes());
        tail.extend_from_slice(&[45, 0, 45, 0, 0, 0, 0, 0, 0, 0, 0, 0]);
        tail.extend_from_slice(&3u64.to_le_bytes());
        tail.extend_from_slice(&3u64.to_le_bytes());
        tail.extend_from_slice(&500u64.to_le_bytes());
        tail.extend_from_slice(&8_000_000_000u64.to_le_bytes());
        tail.extend_from_slice(&EOCD64_LOCATOR.to_le_bytes());
        tail.extend_from_slice(&0u32.to_le_bytes());
        tail.extend_from_slice(&record_at.to_le_bytes());
        tail.extend_from_slice(&1u32.to_le_bytes());
        tail.extend_from_slice(&EOCD.to_le_bytes());
        tail.extend_from_slice(&[0, 0, 0, 0, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0, 0]);
        let found = locate(&tail, record_at).unwrap();
        assert_eq!(found, Directory { offset: 8_000_000_000, size: 500, entries: 3 });
    }

    #[test]
    fn refuses_paths_that_leave_the_folder() {
        assert_eq!(safe_path("Data\\Win32\\a.big").as_deref(), Some("Data/Win32/a.big"));
        assert_eq!(safe_path("./x//y").as_deref(), Some("x/y"));
        assert!(safe_path("../evil").is_none());
        assert!(safe_path("/etc/passwd").is_none());
        assert!(safe_path("C:/x").is_none());
        assert!(safe_path("a/../../b").is_none());
    }

    #[test]
    fn local_header_length_counts_name_and_extra() {
        let mut h = LOCAL.to_le_bytes().to_vec();
        h.extend_from_slice(&[0u8; 22]);
        h.extend_from_slice(&5u16.to_le_bytes());
        h.extend_from_slice(&9u16.to_le_bytes());
        assert_eq!(local_header_len(&h), Some(Ok(44)));
        assert_eq!(local_header_len(&h[..10]), None);
    }
}
