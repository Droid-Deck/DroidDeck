use flate2::{Decompress, FlushDecompress, Status};
use sha1::{Digest, Sha1};

use super::manifest::ChunkInfo;

pub const CHUNK_MAGIC: u32 = 0xB1FE_3AA2;
const MIN_HEADER: usize = 41;

fn le_u32(b: &[u8], at: usize) -> u32 {
    u32::from_le_bytes([b[at], b[at + 1], b[at + 2], b[at + 3]])
}

pub fn decode(body: &[u8], info: &ChunkInfo) -> Result<Vec<u8>, String> {
    if body.len() < MIN_HEADER {
        return Err(format!("chunk body of {} bytes", body.len()));
    }
    if le_u32(body, 0) != CHUNK_MAGIC {
        return Err("not a chunk file".into());
    }
    let header = (le_u32(body, 8) as i32).max(MIN_HEADER as i32) as usize;
    let stored = (le_u32(body, 12) as i32).max(0) as usize;
    let flags = body[40];
    if header > body.len() {
        return Err("chunk header runs past the body".into());
    }
    let limit = info.window as usize;
    let data = if flags & 1 != 0 {
        let end = header.saturating_add(stored).min(body.len());
        inflate(&body[header..end], limit)?
    } else {
        let end = header
            .checked_add(stored)
            .filter(|&e| e <= body.len())
            .ok_or("stored chunk payload is short")?;
        body[header..end].to_vec()
    };
    if data.len() > limit {
        return Err(format!("chunk decodes to {} bytes, more than {limit}", data.len()));
    }
    if info.window_exact && data.len() != limit {
        return Err(format!("chunk decodes to {} bytes, manifest says {limit}", data.len()));
    }
    if let Some(expected) = info.sha1 {
        let got: [u8; 20] = Sha1::digest(&data).into();
        if got != expected {
            return Err("chunk SHA-1 mismatch".into());
        }
    }
    Ok(data)
}

fn inflate(input: &[u8], limit: usize) -> Result<Vec<u8>, String> {
    let mut z = Decompress::new(true);
    let mut out = Vec::with_capacity(limit + 1);
    loop {
        let (in_before, out_before) = (z.total_in(), out.len());
        let consumed = in_before as usize;
        let status = z
            .decompress_vec(&input[consumed..], &mut out, FlushDecompress::Finish)
            .map_err(|e| format!("chunk does not inflate: {e}"))?;
        if status == Status::StreamEnd {
            return Ok(out);
        }
        if out.len() > limit {
            return Err(format!("chunk inflates past {limit} bytes"));
        }
        if out.len() == out.capacity() {
            out.reserve(64 * 1024);
            continue;
        }
        if z.total_in() == in_before && out.len() == out_before {
            return Err("chunk zlib stream is truncated".into());
        }
    }
}

#[cfg(test)]
pub mod testing {
    use super::CHUNK_MAGIC;
    use std::io::Write;

    pub fn encode(data: &[u8], compress: bool) -> Vec<u8> {
        let payload = if compress {
            let mut enc = flate2::write::ZlibEncoder::new(Vec::new(), flate2::Compression::fast());
            enc.write_all(data).unwrap();
            enc.finish().unwrap()
        } else {
            data.to_vec()
        };
        let mut out = Vec::new();
        out.extend_from_slice(&CHUNK_MAGIC.to_le_bytes());
        out.extend_from_slice(&3i32.to_le_bytes());
        out.extend_from_slice(&66i32.to_le_bytes());
        out.extend_from_slice(&(payload.len() as i32).to_le_bytes());
        out.extend_from_slice(&[0u8; 16]);
        out.extend_from_slice(&0u64.to_le_bytes());
        out.push(if compress { 1 } else { 0 });
        out.extend_from_slice(&[0u8; 25]);
        out.extend_from_slice(&payload);
        out
    }
}

#[cfg(test)]
mod tests {
    use super::testing::encode;
    use super::*;
    use crate::epic::manifest::testing::chunk;

    fn info_for(data: &[u8], with_hash: bool) -> ChunkInfo {
        let mut info = chunk(1, data.len() as u32);
        if with_hash {
            info.sha1 = Some(Sha1::digest(data).into());
        }
        info
    }

    #[test]
    fn compressed_and_stored_chunks_decode() {
        let data: Vec<u8> = (0..5000u32).map(|i| (i % 251) as u8).collect();
        for compress in [true, false] {
            assert_eq!(decode(&encode(&data, compress), &info_for(&data, true)).unwrap(), data);
        }
    }

    #[test]
    fn bad_downloads_are_refused() {
        let data = vec![7u8; 4096];
        let info = info_for(&data, true);
        let good = encode(&data, true);
        assert!(decode(&good[..good.len() - 3], &info).is_err());
        let mut wrong_magic = good.clone();
        wrong_magic[0] ^= 1;
        assert!(decode(&wrong_magic, &info).is_err());
        let mut other = info.clone();
        other.sha1 = Some([9; 20]);
        assert!(decode(&good, &other).is_err());
        let mut longer = info.clone();
        longer.window = 4097;
        assert!(decode(&good, &longer).is_err());
        let stored = encode(&data, false);
        assert!(decode(&stored[..stored.len() - 1], &info).is_err());
        assert!(decode(&good[..40], &info).is_err());
    }

    #[test]
    fn an_inexact_window_is_an_upper_bound() {
        let data = vec![1u8; 100];
        let mut info = info_for(&data, false);
        info.window = 1024;
        info.window_exact = false;
        assert_eq!(decode(&encode(&data, true), &info).unwrap().len(), 100);
        info.window = 50;
        assert!(decode(&encode(&data, true), &info).is_err());
    }
}
