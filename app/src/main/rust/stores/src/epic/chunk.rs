use std::io::Write;

use flate2::{Compression, Decompress, FlushDecompress, Status};
use sha1::{Digest, Sha1};

use super::manifest::{ChunkInfo, Guid};

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

/// Window size of the chunked-save machinery: a stored block is always exactly this long,
/// zero-padded when the save data is shorter.
pub const CHUNK_WINDOW: usize = 1024 * 1024;

/// ECMA-182 CRC-64 polynomial, `HashPoly64` in UE's BuildPatchHash.
const HASH_POLY: u64 = 0xC96C_5795_D787_0F42;

const fn rolling_table() -> [u64; 256] {
    let mut table = [0u64; 256];
    let mut i = 0usize;
    while i < 256 {
        let mut v = i as u64;
        let mut round = 0;
        while round < 8 {
            v = if v & 1 != 0 { (v >> 1) ^ HASH_POLY } else { v >> 1 };
            round += 1;
        }
        table[i] = v;
        i += 1;
    }
    table
}

static ROLLING_TABLE: [u64; 256] = rolling_table();

/// UE `FRollingHash::GetHashForDataSet`: the state rotates left one bit per input byte and
/// is XORed with the per-byte table value. Computed over the whole padded 1 MiB block.
pub fn rolling_hash(data: &[u8]) -> u64 {
    let mut state: u64 = 0;
    for &byte in data {
        state = state.rotate_left(1) ^ ROLLING_TABLE[byte as usize];
    }
    state
}

const fn crc32_table() -> [u32; 256] {
    let mut table = [0u32; 256];
    let mut i = 0usize;
    while i < 256 {
        let mut v = i as u32;
        let mut bit = 0;
        while bit < 8 {
            v = if v & 1 != 0 { (v >> 1) ^ 0xEDB8_8320 } else { v >> 1 };
            bit += 1;
        }
        table[i] = v;
        i += 1;
    }
    table
}

static CRC32_TABLE: [u32; 256] = crc32_table();

/// CRC-32 (IEEE), identical to `zlib.crc32`, so chunk group numbers match EGL's without a dependency.
pub fn crc32(data: &[u8]) -> u32 {
    let mut state = 0xFFFF_FFFFu32;
    for &byte in data {
        state = CRC32_TABLE[((state ^ byte as u32) & 0xFF) as usize] ^ (state >> 8);
    }
    !state
}

/// The two-digit `ChunksV4` folder EGL stores a chunk under: crc32 of the 16 GUID bytes mod 100.
pub fn guid_group(guid: &Guid) -> u8 {
    let mut raw = [0u8; 16];
    for (at, word) in guid.iter().enumerate() {
        raw[at * 4..at * 4 + 4].copy_from_slice(&word.to_le_bytes());
    }
    (crc32(&raw) % 100) as u8
}

/// A chunk file built for upload: `bytes` go to the cloud, the rest is what the save
/// manifest repeats about the same chunk.
pub struct BuiltChunk {
    /// The whole chunk file: 66-byte header followed by the zlib payload.
    pub bytes: Vec<u8>,
    /// Rolling hash of the padded block; with the GUID it names the cloud file.
    pub rolling: u64,
    /// SHA-1 of the padded block.
    pub sha1: [u8; 20],
    /// Stored (compressed) payload length; the manifest's CDN size for this chunk.
    pub stored: usize,
}

impl BuiltChunk {
    /// The save manifest's entry for this chunk.
    pub fn info(&self, guid: &Guid) -> ChunkInfo {
        ChunkInfo {
            guid: *guid,
            rolling_hash: self.rolling,
            sha1: Some(self.sha1),
            group: guid_group(guid) as u64,
            window: CHUNK_WINDOW as u32,
            window_exact: true,
            cdn_size: self.bytes.len() as u64,
        }
    }
}

/// Builds a cloud chunk from a block of save data (at most one window long: the packer
/// hands over whole ones). The block is zero-padded to the full 1 MiB window before both
/// hashes, and always stored zlib-compressed at EGL's level 6,
/// even when that grows the payload. The pure-Rust deflate backend may emit different
/// bytes than EGL's zlib (so `stored` and the CDN size differ); nothing else in the file
/// depends on the compressor.
pub fn encode(data: &[u8], guid: &Guid) -> Result<BuiltChunk, String> {
    let mut block = vec![0u8; CHUNK_WINDOW];
    block[..data.len()].copy_from_slice(data);
    let sha1: [u8; 20] = Sha1::digest(&block).into();
    let rolling = rolling_hash(&block);
    let mut encoder = flate2::write::ZlibEncoder::new(Vec::new(), Compression::new(6));
    encoder.write_all(&block).map_err(|e| format!("chunk does not compress: {e}"))?;
    let payload = encoder.finish().map_err(|e| format!("chunk does not compress: {e}"))?;
    let mut out = Vec::with_capacity(66 + payload.len());
    out.extend_from_slice(&CHUNK_MAGIC.to_le_bytes());
    out.extend_from_slice(&3i32.to_le_bytes());
    out.extend_from_slice(&66i32.to_le_bytes());
    out.extend_from_slice(&(payload.len() as i32).to_le_bytes());
    for word in guid {
        out.extend_from_slice(&word.to_le_bytes());
    }
    out.extend_from_slice(&rolling.to_le_bytes());
    out.push(1); // storedAs: zlib
    out.extend_from_slice(&sha1);
    out.push(3); // hashType: SHA-1
    out.extend_from_slice(&(CHUNK_WINDOW as u32).to_le_bytes());
    out.extend_from_slice(&payload);
    Ok(BuiltChunk { bytes: out, rolling, sha1, stored: payload.len() })
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

#[cfg(test)]
mod write_tests {
    use super::*;

    /// Values below were computed with the byte-verified reference implementation
    /// (`verify_format.py` in the droiddeck/epic-cloud-backup capture workspace outside
    /// this repository; UE FRollingHash / zlib.crc32), never guessed.
    #[test]
    fn rolling_hash_matches_reference_vectors() {
        let mut abc = b"abc".to_vec();
        abc.resize(CHUNK_WINDOW, 0);
        // A deterministic pseudo-random fill with prime period 251 so the block does not
        // end on a pattern boundary.
        let pseudo: Vec<u8> = (0..CHUNK_WINDOW).map(|i| (((i % 251) * 7) % 256) as u8).collect();
        // Sanity only: the all-zero block maps to zero for any state-0 implementation.
        assert_eq!(rolling_hash(&[0u8; CHUNK_WINDOW]), 0x0000_0000_0000_0000);
        assert_eq!(rolling_hash(&abc), 0xF793_A713_2130_2674);
        assert_eq!(rolling_hash(&pseudo), 0x13C2_DA4C_404B_DBCE);
    }

    #[test]
    fn crc32_and_group_numbers_match_the_reference() {
        let cases: [(Guid, u32, u8); 3] = [
            ([0x1234_5678, 0x9ABC_DEF0, 0x0FED_CBA9, 0x8765_4321], 0x0ACF_DBBB, 63),
            ([1, 2, 3, 4], 0xAF05_D4EF, 91),
            ([0xDEAD_BEEF, 0xCAFE_BABE, 0xFEED_FACE, 0x0DDC_0FFE], 0x0DE7_6340, 32),
        ];
        for (guid, crc, group) in cases {
            let mut raw = Vec::new();
            for word in guid {
                raw.extend_from_slice(&word.to_le_bytes());
            }
            assert_eq!(crc32(&raw), crc, "{guid:08X?}");
            assert_eq!(guid_group(&guid), group, "{guid:08X?}");
        }
    }

    #[test]
    fn encode_writes_the_egl_layout_and_decode_round_trips() {
        let data: Vec<u8> = (0..5000u32).map(|i| (i % 251) as u8).collect();
        let guid: Guid = [7, 8, 0x3333_3333, 0x4444_4407];
        let built = encode(&data, &guid).unwrap();
        assert_eq!(&built.bytes[0..4], &CHUNK_MAGIC.to_le_bytes());
        assert_eq!(&built.bytes[4..8], &3i32.to_le_bytes());
        assert_eq!(&built.bytes[8..12], &66i32.to_le_bytes());
        assert_eq!(&built.bytes[12..16], &(built.stored as i32).to_le_bytes());
        for (at, word) in guid.iter().enumerate() {
            assert_eq!(&built.bytes[16 + at * 4..20 + at * 4], &word.to_le_bytes());
        }
        assert_eq!(built.bytes[40], 1); // storedAs: zlib
        assert_eq!(built.bytes[61], 3); // hashType: SHA-1
        assert_eq!(&built.bytes[62..66], &(CHUNK_WINDOW as u32).to_le_bytes());
        let mut block = data.clone();
        block.resize(CHUNK_WINDOW, 0);
        // Both hashes against the reference implementation's values for this exact block,
        // not against our own recomputation.
        assert_eq!(built.rolling, 0xC077_BA6A_E8A6_52AE);
        assert_eq!(
            built.sha1,
            [
                0x87, 0x00, 0x6a, 0xc1, 0x83, 0x57, 0x0d, 0xd1, 0x94, 0x97, 0x31, 0xc1, 0xa6,
                0xa9, 0xe6, 0x70, 0x28, 0x38, 0x3d, 0x54,
            ]
        );
        assert_eq!(&built.bytes[32..40], &built.rolling.to_le_bytes());
        assert_eq!(&built.bytes[41..61], &built.sha1);
        assert_eq!(decode(&built.bytes, &built.info(&guid)).unwrap(), block);
    }
}
