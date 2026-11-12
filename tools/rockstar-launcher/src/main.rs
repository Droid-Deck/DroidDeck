#![cfg_attr(windows, windows_subsystem = "windows")]

#[cfg(windows)]
fn log(message: &str) {
    use std::io::Write;
    let Ok(exe) = std::env::current_exe() else {
        return;
    };
    let path = exe.with_file_name("launcher.log");
    if let Ok(metadata) = std::fs::symlink_metadata(&path) {
        if !metadata.is_file() || metadata.len() > 65536 {
            return;
        }
    }
    if let Ok(mut file) = std::fs::OpenOptions::new()
        .create(true)
        .append(true)
        .open(path)
    {
        let _ = writeln!(file, "{message}");
    }
}

#[cfg(windows)]
fn run() -> Result<i32, Box<dyn std::error::Error>> {
    use droiddeck_rockstar_launcher::{pipe::Listener, wait_for_game, Frame, Length};
    use std::io::Read;
    use std::time::{Duration, SystemTime, UNIX_EPOCH};

    let config_path = std::env::current_exe()?.with_file_name("launch.json");
    let mut data = Vec::new();
    std::fs::File::open(&config_path)?
        .take(16385)
        .read_to_end(&mut data)?;
    std::fs::remove_file(&config_path)?;
    if data.len() > 16384 {
        return Err("launch handoff exceeds size limit".into());
    }
    let config: serde_json::Value = serde_json::from_slice(&data)?;
    let text = |key| {
        config
            .get(key)
            .and_then(|value| value.as_str())
            .ok_or("missing launch field")
    };
    let expires = config
        .get("expiresAt")
        .and_then(|value| value.as_u64())
        .ok_or("missing launch expiry")?;
    let now = SystemTime::now().duration_since(UNIX_EPOCH)?.as_millis() as u64;
    if expires <= now || expires - now > 120_000 {
        return Err("launch handoff expired".into());
    }
    let material = text("material")?.as_bytes().to_vec();
    if material.len() != 640 || !material.iter().all(u8::is_ascii_hexdigit) {
        return Err("invalid launch material".into());
    }
    let pipe_name = text("pipe")?.to_owned();
    if !matches!(
        pipe_name.as_str(),
        "GTA3_Pipe" | "GTA-VC_Pipe" | "GTA_SA_Pipe"
    ) {
        return Err("unsupported Rockstar launch pipe".into());
    }
    let root = std::fs::canonicalize(text("folder")?)?;
    let exe = std::fs::canonicalize(text("exe")?)?;
    let workdir = std::fs::canonicalize(text("workdir")?)?;
    if !exe.is_file()
        || !exe.starts_with(&root)
        || !workdir.starts_with(&root)
        || !root.join("MTLX.dll").is_file()
    {
        return Err("invalid Rockstar game path".into());
    }
    if let Some(index) =
        std::env::args().position(|arg| arg.eq_ignore_ascii_case("-launchTitleInFolder"))
    {
        let folder = std::env::args()
            .nth(index + 1)
            .ok_or("missing game folder")?;
        if std::fs::canonicalize(folder)? != root {
            return Err("Rockstar launch folder mismatch".into());
        }
    }
    let args = config
        .get("args")
        .and_then(|value| value.as_array())
        .ok_or("missing game arguments")?;
    let args: Result<Vec<&str>, _> = args
        .iter()
        .map(|value| value.as_str().ok_or("invalid game argument"))
        .collect();
    log("launch handoff accepted");
    let listener = Listener::new(&pipe_name, Duration::from_secs(30))?;
    log("launcher pipe created");
    let mut child = std::process::Command::new(exe)
        .current_dir(workdir)
        .args(args?)
        .spawn()?;
    let pid = child.id();
    log("game process started");
    let (result, handshake) = std::sync::mpsc::sync_channel(1);
    std::thread::spawn(move || {
        let serve = || -> std::io::Result<()> {
            let mut pipe = listener.accept()?;
            log("launcher pipe connected");
            if pipe.client_pid()? != pid {
                return Err(std::io::Error::new(
                    std::io::ErrorKind::PermissionDenied,
                    "unexpected Rockstar pipe client",
                ));
            }
            let request = Frame::read(&mut pipe, Length::Total)?;
            log("game greeting received");
            if request.tag != *b"COLE" || !request.payload.is_empty() {
                return Err(std::io::Error::new(
                    std::io::ErrorKind::InvalidData,
                    "unexpected Rockstar greeting",
                ));
            }
            Frame {
                tag: *b"DUNN",
                payload: material,
                nul_terminated: false,
            }
            .write(&mut pipe, Length::Body)?;
            log("launcher handshake complete");
            Ok(())
        };
        let outcome = serve();
        if let Err(error) = &outcome {
            log(&format!("launcher handshake failed: {error}"));
        }
        let _ = result.send(outcome);
    });
    Ok(wait_for_game(&mut child, handshake)?.code().unwrap_or(1))
}

fn main() {
    #[cfg(windows)]
    let code = match run() {
        Ok(code) => code,
        Err(error) => {
            log(&format!("launch failed: {error}"));
            eprintln!("Rockstar launch failed: {error}");
            1
        }
    };
    #[cfg(not(windows))]
    let code = 1;
    std::process::exit(code);
}
