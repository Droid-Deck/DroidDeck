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

#[derive(Debug)]
pub struct Manifest {
    pub compression: u64,
    pub files: Vec<File>,
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

/// Files of the chunks to install: those for every language, plus the language asked for.
pub fn parse(decoded: &[u8], language: &str) -> Result<Manifest, String> {
    let m = pb::fields(decoded)?;
    let wanted = language.to_ascii_lowercase();
    let prefix = wanted.split(['-', '_']).next().unwrap_or("").to_string();
    let mut files = Vec::new();
    for c in pb::subs(&m, 4) {
        let c = pb::fields(c)?;
        let lang = pb::string(&c, 5).unwrap_or_default().to_ascii_lowercase();
        let keep = lang.is_empty() || lang == wanted || (!prefix.is_empty() && lang.split(['-', '_']).next() == Some(prefix.as_str()));
        if !keep {
            continue;
        }
        for f in pb::subs(&c, 3) {
            files.push(file(f)?);
        }
    }
    Ok(Manifest { compression: pb::num(&m, 21).unwrap_or(0), files })
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
