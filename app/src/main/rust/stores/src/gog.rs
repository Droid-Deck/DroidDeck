
pub mod engine;
pub mod jni;
pub mod plan;
pub mod maintenance;

use std::fs::File;
use std::io::Read;
use std::path::Path;

pub fn to_hex(bytes: &[u8]) -> String {
    const DIGITS: &[u8; 16] = b"0123456789abcdef";
    let mut out = String::with_capacity(bytes.len() * 2);
    for b in bytes {
        out.push(DIGITS[(b >> 4) as usize] as char);
        out.push(DIGITS[(b & 0x0f) as usize] as char);
    }
    out
}

pub fn md5_hex(data: &[u8]) -> String {
    let mut hasher = crate::md5_small::Md5::new();
    hasher.update(data);
    to_hex(&hasher.finalize())
}

pub fn md5_hex_file(path: &Path) -> Option<String> {
    let mut file = File::open(path).ok()?;
    let mut hasher = crate::md5_small::Md5::new();
    let mut buf = vec![0u8; 128 * 1024];
    loop {
        let n = file.read(&mut buf).ok()?;
        if n == 0 {
            break;
        }
        hasher.update(&buf[..n]);
    }
    Some(to_hex(&hasher.finalize()))
}

pub fn inflate_zlib(data: &[u8]) -> Option<Vec<u8>> {
    if data.len() < 2 || data[0] != 0x78 {
        return None;
    }
    let mut out = Vec::with_capacity(data.len().saturating_mul(3));
    let mut decoder = flate2::read::ZlibDecoder::new(data);
    match decoder.read_to_end(&mut out) {
        Ok(_) => Some(out),
        Err(_) => None,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn hex_is_lowercase_and_padded() {
        assert_eq!(to_hex(&[0x00, 0x0a, 0xff, 0x10]), "000aff10");
    }

    #[test]
    fn inflate_roundtrip_and_stored_fallback() {
        use flate2::write::ZlibEncoder;
        use flate2::Compression;
        use std::io::Write;
        let payload = b"hello gog chunk payload".repeat(64);
        let mut enc = ZlibEncoder::new(Vec::new(), Compression::default());
        enc.write_all(&payload).unwrap();
        let compressed = enc.finish().unwrap();
        assert_eq!(compressed[0], 0x78);
        assert_eq!(inflate_zlib(&compressed).unwrap(), payload);
        assert!(inflate_zlib(b"\x1f\x8bnot zlib").is_none());
        assert!(inflate_zlib(b"\x78").is_none());
        let mut truncated = compressed.clone();
        let mid = truncated.len() / 2;
        truncated.truncate(mid);
        assert!(inflate_zlib(&truncated).map(|out| out != payload).unwrap_or(true));
        let mut corrupt = compressed.clone();
        for b in corrupt.iter_mut().skip(2) {
            *b = !*b;
        }
        assert!(inflate_zlib(&corrupt).is_none() || inflate_zlib(&corrupt).unwrap() != payload);
    }

    #[test]
    fn md5_hex_matches_rfc1321_vector() {
        assert_eq!(md5_hex(b""), "d41d8cd98f00b204e9800998ecf8427e");
        assert_eq!(md5_hex(b"abc"), "900150983cd24fb0d6963f7d28e17f72");
    }
}
