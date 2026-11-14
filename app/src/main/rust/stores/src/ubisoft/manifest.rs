use std::io::Read;

use super::pb;

/// Bytes Ubisoft prepends to a downloaded `.manifest` before the zlib stream.
pub const HEADER: usize = 356;
pub const DEFLATE: u64 = 1;
pub const ZSTD: u64 = 3;

#[derive(Clone, Debug, PartialEq)]
pub struct Slice {
    pub size: u32,
    pub download_size: Option<u32>,
    pub download_sha1: Vec<u8>,
    pub content_sha1: Vec<u8>,
    pub offset: u64,
}

#[derive(Clone, Debug, PartialEq)]
pub struct File {
    pub name: String,
    pub size: u64,
    pub dir: bool,
    pub slices: Vec<Slice>,
}

/// A chunk the install keeps, as the game's launcher session reports it.
#[derive(Clone, Debug, PartialEq)]
pub struct Chunk {
    pub id: u32,
    pub tags: String,
}

#[derive(Debug)]
pub struct Manifest {
    pub compression: u64,
    pub files: Vec<File>,
    pub chunks: Vec<Chunk>,
    /// Files of chunks left out: other languages, and add-ons the account does not own.
    pub skipped: Vec<String>,
    /// Prerequisite installers Connect runs after an install: exe, arguments, description.
    pub install_runs: Vec<(String, String, String)>,
    /// Registry keys Connect writes for the game, with their string values as (name, value).
    pub registry: Vec<(String, Vec<(String, String)>)>,
}

fn file(b: &[u8]) -> Result<File, String> {
    let f = pb::fields(b)?;
    let hashes = pb::subs(&f, 4);
    let mut offset = 0u64;
    let mut slices = Vec::new();
    for (i, s) in pb::subs(&f, 7).into_iter().enumerate() {
        let s = pb::fields(s)?;
        let size = pb::num(&s, 1).unwrap_or(0) as u32;
        let at = pb::num(&s, 4).unwrap_or(offset);
        slices.push(Slice {
            size,
            download_size: pb::num(&s, 2).map(|v| v as u32),
            download_sha1: pb::sub(&s, 3).unwrap_or_default().to_vec(),
            content_sha1: hashes.get(i).map(|h| h.to_vec()).unwrap_or_default(),
            offset: at,
        });
        offset = at + size as u64;
    }
    if slices.is_empty() {
        for h in &hashes {
            slices.push(Slice { size: 0, download_size: None, download_sha1: Vec::new(), content_sha1: h.to_vec(), offset: 0 });
        }
    }
    Ok(File {
        name: pb::string(&f, 1).unwrap_or_default().replace('\\', "/"),
        size: pb::num(&f, 2).unwrap_or(0),
        dir: pb::num(&f, 3) == Some(1),
        slices,
    })
}

/// Files of the chunks to install: those for every language plus the language asked for, of the
/// game itself or an optional add-on in `owned` (every add-on when `owned` is empty).
pub fn parse(decoded: &[u8], language: &str) -> Result<Manifest, String> {
    parse_owned(decoded, language, &[])
}

pub fn parse_owned(decoded: &[u8], language: &str, owned: &[u32]) -> Result<Manifest, String> {
    let m = pb::fields(decoded)?;
    let wanted = language.to_ascii_lowercase();
    let prefix = wanted.split(['-', '_']).next().unwrap_or("").to_string();
    let mut files = Vec::new();
    let mut chunks = Vec::new();
    let mut skipped = Vec::new();
    for c in pb::subs(&m, 4) {
        let c = pb::fields(c)?;
        let lang = pb::string(&c, 5).unwrap_or_default().to_ascii_lowercase();
        let mut products: Vec<u32> = c.iter().filter_map(|(n, v)| match v { pb::Value::Varint(x) if *n == 8 => Some(*x as u32), _ => None }).collect();
        products.extend(pb::num(&c, 4).map(|v| v as u32));
        let in_language = lang.is_empty() || lang == wanted || (!prefix.is_empty() && lang.split(['-', '_']).next() == Some(prefix.as_str()));
        let optional = pb::num(&c, 2) == Some(1);
        let owned_product = !optional || owned.is_empty() || products.is_empty() || products.iter().any(|p| owned.contains(p));
        if !(in_language && owned_product) {
            for f in pb::subs(&c, 3) {
                skipped.push(file(f)?.name);
            }
            continue;
        }
        chunks.push(Chunk { id: pb::num(&c, 1).unwrap_or(0) as u32, tags: pb::string(&c, 7).unwrap_or_default() });
        for f in pb::subs(&c, 3) {
            files.push(file(f)?);
        }
    }
    let kept: std::collections::HashSet<&str> = files.iter().map(|f| f.name.as_str()).collect();
    skipped.retain(|n| !kept.contains(n.as_str()));
    let mut install_runs = Vec::new();
    for r in pb::subs(&m, 2) {
        let r = pb::fields(r)?;
        install_runs.push((pb::string(&r, 1).unwrap_or_default(), pb::string(&r, 3).unwrap_or_default(), pb::string(&r, 4).unwrap_or_default()));
    }
    let mut registry = Vec::new();
    for r in pb::subs(&m, 5) {
        let r = pb::fields(r)?;
        let mut values = Vec::new();
        for e in pb::subs(&r, 3) {
            let e = pb::fields(e)?;
            values.push((pb::string(&e, 1).unwrap_or_default(), pb::string(&e, 2).unwrap_or_default()));
        }
        registry.push((pb::string(&r, 1).unwrap_or_default(), values));
    }
    Ok(Manifest { compression: pb::num(&m, 21).unwrap_or(0), files, chunks, skipped, install_runs, registry })
}

pub fn decode(downloaded: &[u8]) -> Result<Vec<u8>, String> {
    if downloaded.len() <= HEADER {
        return Err(format!("the manifest is only {} bytes", downloaded.len()));
    }
    let mut out = Vec::new();
    flate2::read::ZlibDecoder::new(&downloaded[HEADER..]).read_to_end(&mut out).map_err(|e| format!("the manifest did not inflate: {e}"))?;
    Ok(out)
}

pub fn hex_upper(b: &[u8]) -> String {
    b.iter().map(|x| format!("{x:02X}")).collect()
}

/// The CDN bucket of a slice: the first byte of its hash with nibbles swapped, mapped onto base32.
pub fn bucket(hash_upper: &str) -> char {
    const BASE32: &[u8; 32] = b"0123456789abcdefghijklmnopqrstuv";
    let b = hash_upper.as_bytes();
    let swapped = format!("{}{}", *b.get(1).unwrap_or(&b'0') as char, *b.first().unwrap_or(&b'0') as char);
    let v = u8::from_str_radix(&swapped, 16).unwrap_or(0);
    BASE32[(v / 16) as usize + if v % 2 == 0 { 0 } else { 16 }] as char
}

pub fn slice_path(bucketed: bool, hash_upper: &str) -> String {
    if bucketed { format!("slices_v3/{}/{}", bucket(hash_upper), hash_upper) } else { format!("slices/{hash_upper}") }
}

pub fn decompress(method: u64, data: &[u8]) -> Result<Vec<u8>, String> {
    match method {
        ZSTD => {
            let mut out = Vec::new();
            ruzstd::decoding::StreamingDecoder::new(data).map_err(|e| format!("zstd: {e}"))?.read_to_end(&mut out).map_err(|e| format!("zstd: {e}"))?;
            Ok(out)
        }
        DEFLATE => {
            let zlib = data.len() >= 2 && data[0] == 0x78;
            let inflate = |z: bool| {
                let mut out = Vec::new();
                let r = if z { flate2::read::ZlibDecoder::new(data).read_to_end(&mut out) } else { flate2::read::DeflateDecoder::new(data).read_to_end(&mut out) };
                r.map(|_| out)
            };
            inflate(zlib).or_else(|_| inflate(!zlib)).map_err(|e| format!("deflate: {e}"))
        }
        _ => Ok(data.to_vec()),
    }
}

#[cfg(test)]
mod tests {
    use super::super::pb::Msg;
    use super::*;

    #[test]
    fn buckets_follow_the_swapped_first_byte() {
        assert_eq!(bucket("10ABCDEF"), 'g');
        assert_eq!(bucket("01ABCDEF"), '1');
        assert_eq!(slice_path(true, "01AB"), "slices_v3/1/01AB");
        assert_eq!(slice_path(false, "01AB"), "slices/01AB");
    }

    #[test]
    fn keeps_shared_and_wanted_language_chunks() {
        let f = |name: &str| Msg::new().text(1, name).uint(2, 10).flag(3, false)
            .bytes(4, &[0xAA]).msg(7, Msg::new().uint(1, 10).uint(2, 4).bytes(3, &[0xBB]));
        let m = Msg::new()
            .msg(4, Msg::new().uint(1, 1).uint(2, 0).msg(3, f("Game\\game.exe")))
            .msg(4, Msg::new().uint(1, 2).uint(2, 1).msg(3, f("en.pak")).text(5, "en-US"))
            .msg(4, Msg::new().uint(1, 3).uint(2, 1).msg(3, f("fr.pak")).text(5, "fr-FR"))
            .uint(21, ZSTD);
        let parsed = parse(&m.0, "en-US").unwrap();
        let names: Vec<_> = parsed.files.iter().map(|f| f.name.as_str()).collect();
        assert_eq!(names, ["Game/game.exe", "en.pak"]);
        assert_eq!(parsed.compression, ZSTD);
        assert_eq!(parsed.files[0].slices[0], Slice { size: 10, download_size: Some(4), download_sha1: vec![0xBB], content_sha1: vec![0xAA], offset: 0 });
    }

    #[test]
    fn leaves_out_add_ons_the_account_does_not_own() {
        let f = |name: &str| Msg::new().text(1, name).uint(2, 10).flag(3, false);
        let m = Msg::new()
            .msg(4, Msg::new().uint(1, 1).uint(2, 0).msg(3, f("base.forge")).uint(4, 9999))
            .msg(4, Msg::new().uint(1, 2).uint(2, 1).msg(3, f("dlc_24/east.forge")).uint(4, 5450))
            .msg(4, Msg::new().uint(1, 3).uint(2, 1).msg(3, f("dlc_25/west.forge")).uint(8, 5451).uint(8, 5452))
            .uint(21, ZSTD);
        let parsed = parse_owned(&m.0, "en-US", &[5405, 5452]).unwrap();
        let names: Vec<_> = parsed.files.iter().map(|f| f.name.as_str()).collect();
        assert_eq!(names, ["base.forge", "dlc_25/west.forge"]);
        assert_eq!(parsed.skipped, ["dlc_24/east.forge"]);
        assert_eq!(parsed.chunks.iter().map(|c| c.id).collect::<Vec<_>>(), [1, 3]);
    }

    #[test]
    fn inflates_both_deflate_framings() {
        use std::io::Write;
        let mut z = flate2::write::ZlibEncoder::new(Vec::new(), flate2::Compression::default());
        z.write_all(b"hello").unwrap();
        assert_eq!(decompress(DEFLATE, &z.finish().unwrap()).unwrap(), b"hello");
        let mut r = flate2::write::DeflateEncoder::new(Vec::new(), flate2::Compression::default());
        r.write_all(b"hello").unwrap();
        assert_eq!(decompress(DEFLATE, &r.finish().unwrap()).unwrap(), b"hello");
    }
}
