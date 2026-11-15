#[cfg(windows)]
fn run() -> Result<(), Box<dyn std::error::Error>> {
    use droiddeck_rockstar_launcher::{pipe::Pipe, sdk::Message, Frame, Length};
    use std::io::{Read, Write};
    for (path, framed) in [
        (b"\\\\.\\pipe\\ProbeLauncherPipe\0".as_slice(), true),
        (b"\\\\.\\pipe\\RawProbeLauncher_Pipe\0".as_slice(), false),
    ] {
        let name = std::str::from_utf8(&path[9..path.len() - 1])?;
        let mut pipe = Pipe::connect(name, std::time::Duration::from_secs(10))?;
        let request = b"INIT:42:";
        if framed {
            let mut bytes = (request.len() as u32).to_le_bytes().to_vec();
            bytes.extend_from_slice(request);
            pipe.write_all(&bytes)?;
            let response = Frame::read(&mut pipe, Length::Body)?;
            if response.tag != *b"TINI" || response.payload != b"launcher:42:" {
                return Err("invalid framed launcher identity reply".into());
            }
        } else {
            pipe.write_all(b"INIT:4")?;
            std::thread::sleep(std::time::Duration::from_millis(20));
            pipe.write_all(b"2:")?;
            let mut response = vec![0; b"TINI:launcher:42:".len()];
            pipe.read_exact(&mut response)?;
            if response != b"TINI:launcher:42:" {
                return Err("invalid raw launcher identity reply".into());
            }
        }
    }
    let args: Vec<_> = std::env::args().collect();
    let instance: u32 = args
        .iter()
        .find_map(|arg| arg.strip_prefix("-processInstance="))
        .ok_or("missing instance")?
        .parse()?;
    let output = args
        .windows(2)
        .find(|pair| pair[0] == "--result")
        .map(|pair| &pair[1])
        .ok_or("missing result path")?;
    let mut pipe = Pipe::connect(
        &format!("chrome.rgsc_gameinst_{instance}"),
        std::time::Duration::from_secs(10),
    )?;
    if Message::read(&mut pipe)?.kind != 0xffff {
        return Err("missing transport hello".into());
    }
    Message {
        routing: i32::MAX,
        kind: 0x40000,
        flags: 2,
        payload: 4u32.to_le_bytes().to_vec(),
    }
    .write(&mut pipe)?;
    let expected = [0x30002, 0x30003, 0x30004, 0x30000];
    for kind in expected {
        let message = Message::read(&mut pipe)?;
        if message.kind != kind || message.routing != i32::MAX || message.flags != 2 {
            return Err("unexpected SDK message envelope".into());
        }
        if kind == 0x30002 && message.payload != [3, 0, 0, 0, 123, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0]
        {
            return Err("unexpected fixture account".into());
        }
        if kind == 0x30000 && message.payload != 4u32.to_le_bytes() {
            return Err("launcher hello lost its context".into());
        }
    }
    std::fs::write(output, "SDK handshake accepted")?;
    Ok(())
}

fn main() {
    #[cfg(windows)]
    if let Err(error) = run() {
        eprintln!("SDK probe failed: {error}");
        std::process::exit(1);
    }
}
