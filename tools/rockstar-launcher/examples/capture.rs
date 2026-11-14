#[cfg(windows)]
fn main() -> std::io::Result<()> {
    use droiddeck_rockstar_launcher::{pipe::Pipe, Frame, Length};
    use std::fs::OpenOptions;
    use std::io::{self, Read, Write};
    use std::time::Duration;

    struct Recording<'a> {
        source: &'a mut Pipe,
        output: &'a mut std::fs::File,
    }

    impl Read for Recording<'_> {
        fn read(&mut self, buffer: &mut [u8]) -> io::Result<usize> {
            let size = self.source.read(buffer)?;
            self.output.write_all(&buffer[..size])?;
            Ok(size)
        }
    }

    let args: Vec<_> = std::env::args_os().collect();
    let output = args
        .windows(2)
        .find(|pair| pair[0] == "--output")
        .map(|pair| pair[1].clone())
        .map(std::path::PathBuf::from)
        .unwrap_or(std::env::current_exe()?.with_file_name("reference-reply.bin"));
    let mut file = OpenOptions::new()
        .create_new(true)
        .write(true)
        .open(output)?;
    let pipe_name = args
        .windows(2)
        .find(|pair| pair[0] == "--pipe")
        .map(|pair| pair[1].to_string_lossy().into_owned())
        .unwrap_or_else(|| "GTA-VC_Pipe".to_owned());
    let mut pipe = Pipe::connect(&pipe_name, Duration::from_secs(90))?;
    Frame {
        tag: *b"COLE",
        payload: Vec::new(),
        nul_terminated: true,
    }
    .write(&mut pipe, Length::Total)?;
    let reply = Frame::read(
        &mut Recording {
            source: &mut pipe,
            output: &mut file,
        },
        Length::Body,
    );
    file.sync_all()?;
    let reply = reply?;
    println!(
        "Received {} ({} payload bytes)",
        String::from_utf8_lossy(&reply.tag),
        reply.payload.len()
    );
    Ok(())
}

#[cfg(not(windows))]
fn main() {
    eprintln!("The reference pipe client runs on Windows or Wine.");
    std::process::exit(1);
}
