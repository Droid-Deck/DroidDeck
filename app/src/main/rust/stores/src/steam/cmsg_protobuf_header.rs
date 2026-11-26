use crate::steam::proto_wire::{Reader, WireType, Writer};

pub const INVALID_JOB_ID: u64 = u64::MAX;

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct CMsgProtoBufHeader {
    pub steamid: u64,
    pub client_sessionid: i32,
    pub routing_appid: u32,
    pub jobid_source: u64,
    pub jobid_target: u64,
    pub target_job_name: String,
    pub eresult: i32,
    pub error_message: String,
    pub realm: u32,
    pub messageid: u64,
    pub token_id: u64,
}

impl Default for CMsgProtoBufHeader {
    fn default() -> Self {
        Self {
            steamid: 0,
            client_sessionid: 0,
            routing_appid: 0,
            jobid_source: INVALID_JOB_ID,
            jobid_target: INVALID_JOB_ID,
            target_job_name: String::new(),
            eresult: -1,
            error_message: String::new(),
            realm: 0,
            messageid: 0,
            token_id: 0,
        }
    }
}

impl CMsgProtoBufHeader {
    pub fn serialize(&self, out: &mut Vec<u8>) {
        let mut writer = Writer::new(out);

        if self.steamid != 0 {
            writer.tag(1, WireType::Fixed64);
            writer.fixed64_field_force_body(self.steamid);
        }
        writer.int32_field(2, self.client_sessionid);
        writer.uint32_field(3, self.routing_appid);
        if self.jobid_source != INVALID_JOB_ID {
            writer.tag(10, WireType::Fixed64);
            writer.fixed64_field_force_body(self.jobid_source);
        }
        if self.jobid_target != INVALID_JOB_ID {
            writer.tag(11, WireType::Fixed64);
            writer.fixed64_field_force_body(self.jobid_target);
        }
        writer.string_field(12, &self.target_job_name);
        if self.eresult >= 0 {
            writer.tag(13, WireType::Varint);
            writer.varint(self.eresult as i64 as u64);
        }
        writer.string_field(14, &self.error_message);
        writer.uint64_field(18, self.messageid);
        writer.uint32_field(32, self.realm);
        writer.uint64_field(36, self.token_id);
    }

    pub fn deserialize(bytes: &[u8]) -> Option<Self> {
        let mut reader = Reader::new(bytes);
        let mut header = Self::default();

        while !reader.eof() {
            let Some(tag) = reader.next_tag() else {
                return reader.ok().then_some(header);
            };
            match tag.field_number {
                1 => {
                    if tag.wire_type != WireType::Fixed64 {
                        return None;
                    }
                    header.steamid = reader.fixed64()?;
                }
                2 => header.client_sessionid = reader.i32()?,
                3 => header.routing_appid = reader.u32()?,
                10 => {
                    if tag.wire_type != WireType::Fixed64 {
                        return None;
                    }
                    header.jobid_source = reader.fixed64()?;
                }
                11 => {
                    if tag.wire_type != WireType::Fixed64 {
                        return None;
                    }
                    header.jobid_target = reader.fixed64()?;
                }
                12 => header.target_job_name = reader.string()?,
                13 => header.eresult = reader.i32()?,
                14 => header.error_message = reader.string()?,
                18 => header.messageid = reader.u64()?,
                32 => header.realm = reader.u32()?,
                36 => header.token_id = reader.u64()?,
                _ => {
                    if !reader.skip(tag.wire_type) {
                        return None;
                    }
                }
            }
        }

        Some(header)
    }
}

trait WriterFixedBodies {
    fn fixed64_field_force_body(&mut self, v: u64);
}

impl WriterFixedBodies for Writer<'_> {
    fn fixed64_field_force_body(&mut self, v: u64) {
        self.raw_bytes(&v.to_le_bytes());
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn serializes_only_set_job_ids() {
        let h = CMsgProtoBufHeader {
            steamid: 0x1122_3344_5566_7788,
            client_sessionid: 17,
            routing_appid: 480,
            target_job_name: "Authentication.BeginAuthSessionViaCredentials#1".to_string(),
            ..Default::default()
        };
        let mut bytes = Vec::new();
        h.serialize(&mut bytes);
        let parsed = CMsgProtoBufHeader::deserialize(&bytes).unwrap();
        assert_eq!(parsed, h);
        assert!(!bytes
            .windows(2)
            .any(|w| w == [0x51, 0xff] || w == [0x59, 0xff]));
    }

    #[test]
    fn reads_steam_field_numbers() {
        // steammessages_base.proto: eresult = 13, error_message = 14, messageid = 18, realm = 32, token_id = 36.
        let bytes = [
            0x68, 0x05, 0x72, 0x02, b'n', b'o', 0x90, 0x01, 0x07, 0x80, 0x02, 0x01, 0xa0, 0x02, 0x09,
        ];
        let h = CMsgProtoBufHeader::deserialize(&bytes).unwrap();
        assert_eq!(h.eresult, 5);
        assert_eq!(h.error_message, "no");
        assert_eq!(h.messageid, 7);
        assert_eq!(h.realm, 1);
        assert_eq!(h.token_id, 9);
        let mut out = Vec::new();
        h.serialize(&mut out);
        assert_eq!(out, bytes);
    }

    #[test]
    fn rejects_wrong_fixed_wire_type() {
        let bytes = [0x08, 0x01];
        assert_eq!(CMsgProtoBufHeader::deserialize(&bytes), None);
    }
}
