use crate::steam::proto_wire::{Reader, WireType, Writer};

#[derive(Clone, Copy, Debug, Default, Eq, PartialEq)]
pub struct CMsgClientGetDepotDecryptionKey {
    pub depot_id: u32,
    pub app_id: u32,
}

impl CMsgClientGetDepotDecryptionKey {
    pub fn serialize(&self) -> Vec<u8> {
        let mut out = Vec::new();
        let mut w = Writer::new(&mut out);
        w.uint32_field(1, self.depot_id);
        w.uint32_field(2, self.app_id);
        out
    }
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct CMsgClientGetDepotDecryptionKeyResponse {
    pub eresult: i32,
    pub depot_id: u32,
    pub depot_encryption_key: Vec<u8>,
}

impl Default for CMsgClientGetDepotDecryptionKeyResponse {
    fn default() -> Self {
        Self {
            eresult: 2,
            depot_id: 0,
            depot_encryption_key: Vec::new(),
        }
    }
}

impl CMsgClientGetDepotDecryptionKeyResponse {
    pub fn deserialize(body: &[u8]) -> Option<Self> {
        let mut reader = Reader::new(body);
        let mut msg = Self::default();
        while !reader.eof() {
            let Some(tag) = reader.next_tag() else {
                return reader.ok().then_some(msg);
            };
            match (tag.field_number, tag.wire_type) {
                (1, WireType::Varint) => msg.eresult = reader.i32()?,
                (2, WireType::Varint) => msg.depot_id = reader.u32()?,
                (3, WireType::LengthDelimited) => {
                    msg.depot_encryption_key = reader.bytes()?.to_vec()
                }
                _ => {
                    if !reader.skip(tag.wire_type) {
                        return None;
                    }
                }
            }
        }
        Some(msg)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn reads_fields_only_with_their_wire_type() {
        let mut ok = Vec::new();
        let mut w = Writer::new(&mut ok);
        w.int32_field(1, 1);
        w.uint32_field(2, 7);
        w.bytes_field(3, &[9u8; 32]);
        let r = CMsgClientGetDepotDecryptionKeyResponse::deserialize(&ok).unwrap();
        assert_eq!((r.eresult, r.depot_id, r.depot_encryption_key.len()), (1, 7, 32));
        // Field 1 as a length-delimited value is skipped, not read as the eresult.
        let mismatched = [0x0a, 0x01, 0x01];
        let r = CMsgClientGetDepotDecryptionKeyResponse::deserialize(&mismatched).unwrap();
        assert_eq!(r.eresult, 2);
    }
}
