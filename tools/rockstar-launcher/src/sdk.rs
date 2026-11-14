use std::io::{self, Read, Write};

const HEADER: usize = 16;
const MAX_BYTES: usize = 64 * 1024;
const CONTROL: i32 = i32::MAX;

#[derive(PartialEq, Eq)]
pub struct Message {
    pub routing: i32,
    pub kind: u32,
    pub flags: u32,
    pub payload: Vec<u8>,
}

impl std::fmt::Debug for Message {
    fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        formatter
            .debug_struct("Message")
            .field("routing", &self.routing)
            .field("kind", &self.kind)
            .field("flags", &self.flags)
            .field("payload_bytes", &self.payload.len())
            .finish()
    }
}

fn invalid() -> io::Error {
    io::Error::new(io::ErrorKind::InvalidData, "invalid Rockstar SDK message")
}

impl Message {
    pub fn read(reader: &mut impl Read) -> io::Result<Self> {
        let mut header = [0; HEADER];
        reader.read_exact(&mut header)?;
        let size = u32::from_le_bytes(header[..4].try_into().unwrap()) as usize;
        if size > MAX_BYTES - HEADER {
            return Err(invalid());
        }
        let mut payload = vec![0; size];
        reader.read_exact(&mut payload)?;
        Ok(Self {
            routing: i32::from_le_bytes(header[4..8].try_into().unwrap()),
            kind: u32::from_le_bytes(header[8..12].try_into().unwrap()),
            flags: u32::from_le_bytes(header[12..16].try_into().unwrap()),
            payload,
        })
    }

    pub fn write(&self, writer: &mut impl Write) -> io::Result<()> {
        if self.payload.len() > MAX_BYTES - HEADER {
            return Err(invalid());
        }
        let mut bytes = Vec::with_capacity(HEADER + self.payload.len());
        bytes.extend_from_slice(&(self.payload.len() as u32).to_le_bytes());
        bytes.extend_from_slice(&self.routing.to_le_bytes());
        bytes.extend_from_slice(&self.kind.to_le_bytes());
        bytes.extend_from_slice(&self.flags.to_le_bytes());
        bytes.extend_from_slice(&self.payload);
        writer.write_all(&bytes)
    }

    fn control(kind: u32, payload: Payload) -> Self {
        Self {
            routing: CONTROL,
            kind,
            flags: 2,
            payload: payload.0,
        }
    }

    pub fn hello(pid: u32) -> Self {
        Self {
            routing: -2,
            kind: 0xffff,
            flags: 2,
            payload: pid.to_le_bytes().to_vec(),
        }
    }
}

#[derive(Default)]
struct Payload(Vec<u8>);

impl Payload {
    fn raw(mut self, bytes: &[u8]) -> Self {
        self.0.resize((self.0.len() + 3) & !3, 0);
        self.0.extend_from_slice(bytes);
        self
    }

    fn u32(self, value: u32) -> Self {
        self.raw(&value.to_le_bytes())
    }
    fn u64(self, value: u64) -> Self {
        self.raw(&value.to_le_bytes())
    }
    fn string(self, value: &str) -> Self {
        self.u32(value.len() as u32).raw(value.as_bytes())
    }
}

pub struct Session {
    account: u64,
    identity: String,
    ticket: String,
    title: Option<String>,
    api: std::cell::Cell<u32>,
}

impl Session {
    pub fn new(account: u64, identity: String, ticket: String) -> io::Result<Self> {
        if account == 0
            || identity.is_empty()
            || identity.len() > 512
            || ticket.is_empty()
            || ticket.len() > 48 * 1024
            || identity.contains('\0')
            || ticket.contains('\0')
        {
            return Err(invalid());
        }
        Ok(Self {
            account,
            identity,
            ticket,
            title: None,
            api: std::cell::Cell::new(3),
        })
    }

    pub fn with_title(mut self, title: String) -> io::Result<Self> {
        if title.is_empty()
            || title.len() > 64
            || !title.as_bytes()[0].is_ascii_lowercase()
            || !title
                .bytes()
                .all(|value| value.is_ascii_lowercase() || value.is_ascii_digit() || value == b'_')
        {
            return Err(invalid());
        }
        self.title = Some(title);
        Ok(self)
    }

    fn app_sync(&self, challenge: u64) -> io::Result<Message> {
        let mut payload = Payload::default().u32(u32::from(self.title.is_some()));
        if let Some(title) = &self.title {
            payload = payload
                .u64(u64::MAX)
                .u64(0)
                .u64(0)
                .string(title)
                .u32(1)
                .u32(1)
                .string("");
        }
        let mut inner = Vec::new();
        Message::control(0x80006, payload).write(&mut inner)?;
        Ok(Message::control(
            0x80002,
            Payload::default().u64(challenge).string(&base64(&inner)),
        ))
    }

    pub fn login(&self) -> Vec<Message> {
        let offset = u32::from(self.api.get() >= 4);
        vec![
            Message::control(
                0x30001 + offset,
                Payload::default().u32(3).u64(self.account).u32(0),
            ),
            Message::control(
                0x30002 + offset,
                Payload::default()
                    .u64(self.account)
                    .u32(0)
                    .string(&self.identity),
            ),
            Message::control(
                0x30003 + offset,
                Payload::default()
                    .string(&self.identity)
                    .string(&self.ticket),
            ),
        ]
    }

    pub fn respond(&self, message: &Message) -> io::Result<Vec<Message>> {
        match message.kind {
            0x80000 => {
                if message.payload.len() != 8 {
                    return Err(invalid());
                }
                let challenge = u64::from_le_bytes(message.payload[..8].try_into().unwrap());
                Ok(vec![self.app_sync(challenge)?])
            }
            0x40000 => {
                if message.payload.len() < 4 {
                    return Err(invalid());
                }
                let context = u32::from_le_bytes(message.payload[..4].try_into().unwrap());
                self.api.set(context);
                let mut result = self.login();
                result.push(Message::control(0x30000, Payload::default().u32(context)));
                Ok(result)
            }
            0x40005 | 0x40007 => Ok(vec![Message::control(
                0x30004 + u32::from(self.api.get() >= 4),
                Payload::default(),
            )]),
            _ => Ok(Vec::new()),
        }
    }
}

fn base64(bytes: &[u8]) -> String {
    const ALPHABET: &[u8] = b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    let mut output = String::with_capacity(bytes.len().div_ceil(3) * 4);
    for part in bytes.chunks(3) {
        let value = ((part[0] as u32) << 16)
            | ((part.get(1).copied().unwrap_or(0) as u32) << 8)
            | part.get(2).copied().unwrap_or(0) as u32;
        output.push(ALPHABET[(value >> 18) as usize] as char);
        output.push(ALPHABET[((value >> 12) & 63) as usize] as char);
        output.push(if part.len() > 1 {
            ALPHABET[((value >> 6) & 63) as usize] as char
        } else {
            '='
        });
        output.push(if part.len() > 2 {
            ALPHABET[(value & 63) as usize] as char
        } else {
            '='
        });
    }
    output
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Cursor;

    #[test]
    fn app_sync_echoes_challenge_without_inventing_owned_titles() {
        let session = Session::new(123, "name".into(), "ticket".into()).unwrap();
        let request = Message::control(0x80000, Payload::default().u64(42));
        let response = session.respond(&request).unwrap().remove(0);
        assert_eq!(response.kind, 0x80002);
        assert_eq!(&response.payload[..8], &42u64.to_le_bytes());
        assert_eq!(&response.payload[12..], b"BAAAAP///38GAAgAAgAAAAAAAAA=");
        assert_eq!(base64(b"f"), "Zg==");
        assert_eq!(base64(b"fo"), "Zm8=");
        assert_eq!(base64(b"foo"), "Zm9v");
        assert!(session.with_title("../game".into()).is_err());
    }

    #[test]
    fn transport_hello_matches_wire_layout() {
        let mut bytes = Vec::new();
        Message::hello(0x1234).write(&mut bytes).unwrap();
        assert_eq!(
            bytes,
            [4, 0, 0, 0, 254, 255, 255, 255, 255, 255, 0, 0, 2, 0, 0, 0, 52, 18, 0, 0]
        );
    }

    #[test]
    fn login_preserves_string_lengths_and_alignment() {
        let session = Session::new(0x1122334455667788, "a@b".into(), "ticket".into()).unwrap();
        let login = session.login();
        assert_eq!(
            login[0].payload,
            [3, 0, 0, 0, 136, 119, 102, 85, 68, 51, 34, 17, 0, 0, 0, 0]
        );
        assert_eq!(
            login[1].payload,
            [136, 119, 102, 85, 68, 51, 34, 17, 0, 0, 0, 0, 3, 0, 0, 0, 97, 64, 98]
        );
        assert_eq!(
            login[2].payload,
            [3, 0, 0, 0, 97, 64, 98, 0, 6, 0, 0, 0, 116, 105, 99, 107, 101, 116]
        );
    }

    #[test]
    fn init_sends_authenticated_state_before_launcher_hello() {
        let session = Session::new(123, "name".into(), "ticket".into()).unwrap();
        for (api, expected) in [
            (3, [0x30001, 0x30002, 0x30003, 0x30000]),
            (4, [0x30002, 0x30003, 0x30004, 0x30000]),
        ] {
            let request = Message::control(0x40000, Payload::default().u32(api));
            let result = session.respond(&request).unwrap();
            assert_eq!(result.iter().map(|m| m.kind).collect::<Vec<_>>(), expected);
            assert_eq!(
                result[0].payload,
                [3, 0, 0, 0, 123, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0]
            );
            assert_eq!(result[3].payload, api.to_le_bytes());
        }
    }

    #[test]
    fn malformed_frames_and_sessions_are_rejected() {
        assert!(Session::new(0, "name".into(), "ticket".into()).is_err());
        assert!(Session::new(123, "name".into(), "".into()).is_err());
        let session = Session::new(123, "name".into(), "ticket".into()).unwrap();
        assert!(session
            .respond(&Message::control(0x40000, Payload::default()))
            .is_err());
        assert!(Message::read(&mut Cursor::new(vec![255; 16])).is_err());
        assert!(Message::read(&mut Cursor::new(vec![0; 15])).is_err());
    }

    #[test]
    fn concatenated_messages_remain_separate() {
        let session = Session::new(123, "name".into(), "ticket".into()).unwrap();
        let mut bytes = Vec::new();
        for message in session.login() {
            message.write(&mut bytes).unwrap();
        }
        let mut reader = Cursor::new(bytes);
        for message in session.login() {
            assert_eq!(Message::read(&mut reader).unwrap(), message);
        }
    }
}
