use std::io::{self, Write};

const XZ_MAGIC_PREFIX: [u8; 2] = [0xfd, 0x37];

pub const MAX_MESSAGE_BYTES: usize = 256 * 1024 * 1024;

pub fn body(object: &[u8]) -> Result<&[u8], String> {
    if object.len() < 4 {
        return Err("the manifest is too short".into());
    }
    let header_len = u32::from_be_bytes([object[0], object[1], object[2], object[3]]);
    if header_len > i32::MAX as u32 || 4 + header_len as u64 > object.len() as u64 {
        return Err(format!("the manifest has an invalid header size ({header_len})"));
    }
    Ok(&object[4 + header_len as usize..])
}

struct Capped(Vec<u8>);

impl Write for Capped {
    fn write(&mut self, buf: &[u8]) -> io::Result<usize> {
        if self.0.len() + buf.len() > MAX_MESSAGE_BYTES {
            return Err(io::Error::other("the manifest decompresses to more than the allowed size"));
        }
        self.0.extend_from_slice(buf);
        Ok(buf.len())
    }

    fn flush(&mut self) -> io::Result<()> {
        Ok(())
    }
}

pub fn decompress(body: &[u8]) -> Result<Vec<u8>, String> {
    if body.len() < 2 {
        return Ok(body.to_vec());
    }
    let mut input = io::BufReader::new(body);
    let mut out = Capped(Vec::new());
    if body[..2] == XZ_MAGIC_PREFIX {
        lzma_rs::xz_decompress(&mut input, &mut out).map_err(|e| format!("xz body: {e:?}"))?;
    } else {
        lzma_rs::lzma_decompress(&mut input, &mut out).map_err(|e| format!("lzma body: {e:?}"))?;
    }
    Ok(out.0)
}

pub fn message(object: &[u8]) -> Result<Vec<u8>, String> {
    decompress(body(object)?)
}

#[cfg(test)]
pub mod testing {
    pub fn frame(message: &[u8], header: &[u8], xz: bool) -> Vec<u8> {
        let mut body = Vec::new();
        let mut input = std::io::BufReader::new(message);
        if xz {
            lzma_rs::xz_compress(&mut input, &mut body).unwrap();
        } else {
            lzma_rs::lzma_compress(&mut input, &mut body).unwrap();
        }
        let mut out = (header.len() as u32).to_be_bytes().to_vec();
        out.extend_from_slice(header);
        out.extend_from_slice(&body);
        out
    }
}

#[cfg(test)]
mod tests {
    use super::testing::frame;
    use super::*;

    #[test]
    fn both_containers_round_trip() {
        let message: Vec<u8> = (0..5000u32).flat_map(|i| (i % 251).to_le_bytes()).collect();
        let xz = frame(&message, b"\x08\x01", true);
        assert_eq!(&xz[6..8], &XZ_MAGIC_PREFIX);
        assert_eq!(super::message(&xz).unwrap(), message);
        let alone = frame(&message, b"", false);
        assert_eq!(super::message(&alone).unwrap(), message);
    }

    #[test]
    fn framing_is_validated() {
        assert!(body(&[0, 0, 0]).is_err());
        assert!(body(&[0, 0, 0, 5, 1, 2]).is_err());
        assert!(body(&[0x80, 0, 0, 0, 1]).is_err());
        assert_eq!(body(&[0, 0, 0, 1, 9, 7, 8]).unwrap(), &[7, 8]);
        assert_eq!(body(&[0, 0, 0, 0]).unwrap(), &[] as &[u8]);
        assert_eq!(decompress(&[5]).unwrap(), vec![5]);
        assert!(decompress(&[0xfd, 0x37, 1, 2, 3]).is_err());
        assert!(decompress(&[0xff, 0, 0, 0, 1]).is_err(), "invalid LZMA properties byte");
    }
}
