use std::io::{self, Read, Write};

#[cfg(windows)]
pub mod pipe;

pub mod identity;
pub mod sdk;

pub const MAX_FRAME_BYTES: usize = 64 * 1024;
const OVERHEAD: usize = 4 + 4 + 1;

pub fn wait_for_game(
    child: &mut std::process::Child,
    handshake: std::sync::mpsc::Receiver<io::Result<()>>,
) -> io::Result<std::process::ExitStatus> {
    use std::sync::mpsc::RecvTimeoutError;
    loop {
        match handshake.recv_timeout(std::time::Duration::from_millis(10)) {
            Ok(Ok(())) => return child.wait(),
            result @ (Ok(Err(_)) | Err(RecvTimeoutError::Disconnected)) => {
                let error = match result {
                    Ok(Err(error)) => error,
                    _ => io::Error::new(
                        io::ErrorKind::BrokenPipe,
                        "Rockstar handshake worker stopped",
                    ),
                };
                let _ = child.kill();
                child.wait()?;
                return Err(error);
            }
            Err(RecvTimeoutError::Timeout) => {
                if let Some(status) = child.try_wait()? {
                    return Ok(status);
                }
            }
        }
    }
}

#[derive(Clone, Copy)]
pub enum Length {
    Total,
    Body,
}

impl Length {
    fn header_bytes(self) -> usize {
        match self {
            Self::Total => 0,
            Self::Body => 4,
        }
    }
}

#[derive(Debug, PartialEq, Eq)]
pub struct Frame {
    pub tag: [u8; 4],
    pub payload: Vec<u8>,
    pub nul_terminated: bool,
}

fn invalid(message: &'static str) -> io::Error {
    io::Error::new(io::ErrorKind::InvalidData, message)
}

impl Frame {
    pub fn read(reader: &mut impl Read, length: Length) -> io::Result<Self> {
        let mut header = [0; 4];
        reader.read_exact(&mut header)?;
        let size = (u32::from_le_bytes(header) as usize)
            .checked_add(length.header_bytes())
            .ok_or_else(|| invalid("invalid Rockstar pipe frame length"))?;
        if !(OVERHEAD..=MAX_FRAME_BYTES).contains(&size) {
            return Err(invalid("invalid Rockstar pipe frame length"));
        }
        let mut body = vec![0; size - header.len()];
        reader.read_exact(&mut body)?;
        let nul_terminated = body.last() == Some(&0);
        let end = body.len() - usize::from(nul_terminated);
        if body[4] != b':' || body[..4].contains(&b':') || body[..end].contains(&0) {
            return Err(invalid("invalid Rockstar pipe frame envelope"));
        }
        Ok(Self {
            tag: body[..4].try_into().unwrap(),
            payload: body[5..end].to_vec(),
            nul_terminated,
        })
    }

    pub fn write(&self, writer: &mut impl Write, length: Length) -> io::Result<()> {
        let size = self
            .payload
            .len()
            .checked_add(OVERHEAD + usize::from(self.nul_terminated))
            .filter(|size| *size <= MAX_FRAME_BYTES)
            .ok_or_else(|| invalid("Rockstar pipe frame exceeds size limit"))?;
        if self.tag.contains(&0) || self.tag.contains(&b':') || self.payload.contains(&0) {
            return Err(invalid("Rockstar pipe frame contains a delimiter"));
        }
        let mut encoded = Vec::with_capacity(size);
        encoded.extend_from_slice(&((size - length.header_bytes()) as u32).to_le_bytes());
        encoded.extend_from_slice(&self.tag);
        encoded.push(b':');
        encoded.extend_from_slice(&self.payload);
        if self.nul_terminated {
            encoded.push(0);
        }
        writer.write_all(&encoded)?;
        writer.flush()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn launcher_era_reply_uses_total_length_and_a_terminator() {
        let mut bytes = Vec::new();
        Frame {
            tag: *b"DUNN",
            payload: vec![b'A'; 640],
            nul_terminated: true,
        }
        .write(&mut bytes, Length::Total)
        .unwrap();
        assert_eq!(bytes.len(), 650);
        assert_eq!(&bytes[..9], &[138, 2, 0, 0, b'D', b'U', b'N', b'N', b':']);
        assert_eq!(bytes[649], 0);
    }
    use std::io::Cursor;

    #[test]
    #[ignore]
    fn sleeping_game() {
        std::thread::sleep(std::time::Duration::from_secs(30));
    }

    fn sleeping_child() -> std::process::Child {
        std::process::Command::new(std::env::current_exe().unwrap())
            .args(["--exact", "tests::sleeping_game", "--ignored"])
            .stdout(std::process::Stdio::null())
            .stderr(std::process::Stdio::null())
            .spawn()
            .unwrap()
    }

    #[test]
    fn failed_handshake_terminates_and_reaps_the_game() {
        let mut child = sleeping_child();
        let (sender, receiver) = std::sync::mpsc::channel();
        sender
            .send(Err(io::Error::new(io::ErrorKind::TimedOut, "test timeout")))
            .unwrap();
        let start = std::time::Instant::now();
        assert_eq!(
            wait_for_game(&mut child, receiver).unwrap_err().kind(),
            io::ErrorKind::TimedOut
        );
        assert!(start.elapsed() < std::time::Duration::from_secs(3));
        assert!(child.try_wait().unwrap().is_some());
    }

    #[test]
    fn stopped_handshake_worker_terminates_and_reaps_the_game() {
        let mut child = sleeping_child();
        let (sender, receiver) = std::sync::mpsc::channel();
        drop(sender);
        assert_eq!(
            wait_for_game(&mut child, receiver).unwrap_err().kind(),
            io::ErrorKind::BrokenPipe
        );
        assert!(child.try_wait().unwrap().is_some());
    }

    #[test]
    fn an_exited_game_does_not_wait_for_its_handshake() {
        let mut child = std::process::Command::new(std::env::current_exe().unwrap())
            .args(["--exact", "no_test_with_this_name"])
            .stdout(std::process::Stdio::null())
            .stderr(std::process::Stdio::null())
            .spawn()
            .unwrap();
        let (_sender, receiver) = std::sync::mpsc::channel();
        assert!(wait_for_game(&mut child, receiver).unwrap().success());
    }

    struct Fragmented<T>(T);

    impl<T: Read> Read for Fragmented<T> {
        fn read(&mut self, buffer: &mut [u8]) -> io::Result<usize> {
            let size = buffer.len().min(1);
            self.0.read(&mut buffer[..size])
        }
    }

    impl<T: Write> Write for Fragmented<T> {
        fn write(&mut self, buffer: &[u8]) -> io::Result<usize> {
            self.0.write(&buffer[..buffer.len().min(1)])
        }

        fn flush(&mut self) -> io::Result<()> {
            self.0.flush()
        }
    }

    #[test]
    fn reads_observed_greeting_across_partial_reads() {
        let bytes = b"\x0a\0\0\0COLE:\0";
        assert_eq!(
            Frame::read(&mut Fragmented(&bytes[..]), Length::Total).unwrap(),
            Frame {
                tag: *b"COLE",
                payload: Vec::new(),
                nul_terminated: true,
            }
        );
    }

    #[test]
    fn writes_the_observed_total_length_even_with_partial_writes() {
        let mut bytes = Fragmented(Vec::new());
        Frame {
            tag: *b"COLE",
            payload: Vec::new(),
            nul_terminated: true,
        }
        .write(&mut bytes, Length::Total)
        .unwrap();
        assert_eq!(bytes.0, b"\x0a\0\0\0COLE:\0");
    }

    #[test]
    fn keeps_colons_in_payload_and_leaves_the_next_frame_unread() {
        let frame = Frame {
            tag: *b"DUNN",
            payload: b"one:two".to_vec(),
            nul_terminated: false,
        };
        let mut bytes = Vec::new();
        frame.write(&mut bytes, Length::Total).unwrap();
        bytes.extend_from_slice(b"\x0a\0\0\0COLE:\0");
        let mut reader = Cursor::new(bytes);
        assert_eq!(Frame::read(&mut reader, Length::Total).unwrap(), frame);
        assert_eq!(
            Frame::read(&mut reader, Length::Total).unwrap().tag,
            *b"COLE"
        );
    }

    #[test]
    fn rejects_invalid_lengths_before_reading_a_body() {
        for size in [0u32, 4, 8, MAX_FRAME_BYTES as u32 + 1, u32::MAX] {
            let mut reader = Cursor::new(size.to_le_bytes());
            assert_eq!(
                Frame::read(&mut reader, Length::Total).unwrap_err().kind(),
                io::ErrorKind::InvalidData
            );
            assert_eq!(reader.position(), 4);
        }
    }

    #[test]
    fn rejects_truncated_or_malformed_frames() {
        for bytes in [&b"\x0a\0\0"[..], &b"\x0a\0\0\0COLE:"[..]] {
            assert_eq!(
                Frame::read(&mut &bytes[..], Length::Total)
                    .unwrap_err()
                    .kind(),
                io::ErrorKind::UnexpectedEof
            );
        }
        for bytes in [
            b"\x0a\0\0\0COLE=\0",
            b"\x0a\0\0\0CO:E:x",
            b"\x0a\0\0\0CO\0E:\0",
        ] {
            assert_eq!(
                Frame::read(&mut &bytes[..], Length::Total)
                    .unwrap_err()
                    .kind(),
                io::ErrorKind::InvalidData
            );
        }
    }

    #[test]
    fn rejects_invalid_output_before_writing_any_bytes() {
        let mut bytes = Vec::new();
        for frame in [
            Frame {
                tag: *b"DUNN",
                payload: vec![b'x'; MAX_FRAME_BYTES],
                nul_terminated: false,
            },
            Frame {
                tag: *b"DUNN",
                payload: b"one\0two".to_vec(),
                nul_terminated: false,
            },
            Frame {
                tag: *b"CO:E",
                payload: Vec::new(),
                nul_terminated: false,
            },
        ] {
            assert!(frame.write(&mut bytes, Length::Total).is_err());
            assert!(bytes.is_empty());
        }
    }

    #[test]
    fn accepts_the_observed_unterminated_launch_reply_shape() {
        let payload = vec![b'A'; 640];
        let mut bytes = 645u32.to_le_bytes().to_vec();
        bytes.extend_from_slice(b"DUNN:");
        bytes.extend_from_slice(&payload);
        let frame = Frame::read(&mut Fragmented(&bytes[..]), Length::Body).unwrap();
        assert_eq!(bytes.len(), 649);
        assert_eq!(frame.tag, *b"DUNN");
        assert_eq!(frame.payload, payload);
        assert!(!frame.nul_terminated);
        let mut encoded = Vec::new();
        frame.write(&mut encoded, Length::Body).unwrap();
        assert_eq!(encoded, bytes);
    }

    #[test]
    fn presents_a_complete_reply_to_the_pipe_in_one_write() {
        struct Writes(Vec<usize>);
        impl Write for Writes {
            fn write(&mut self, bytes: &[u8]) -> io::Result<usize> {
                self.0.push(bytes.len());
                Ok(bytes.len())
            }
            fn flush(&mut self) -> io::Result<()> {
                Ok(())
            }
        }
        let mut writer = Writes(Vec::new());
        Frame {
            tag: *b"DUNN",
            payload: vec![b'A'; 640],
            nul_terminated: false,
        }
        .write(&mut writer, Length::Body)
        .unwrap();
        assert_eq!(writer.0, vec![649]);
    }
}
