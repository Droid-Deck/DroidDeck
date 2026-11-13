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
    use droiddeck_rockstar_launcher::{
        pipe::Listener,
        sdk::{Message, Session},
        wait_for_game, Frame, Length,
    };
    use std::io::{Read, Write};
    use std::time::{Duration, SystemTime, UNIX_EPOCH};

    let config_path = std::env::current_exe()?.with_file_name("launch.json");
    let mut data = Vec::new();
    std::fs::File::open(&config_path)?
        .take(65537)
        .read_to_end(&mut data)?;
    std::fs::remove_file(&config_path)?;
    if data.len() > 65536 {
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
    let legacy = if config.get("pipe").is_some() {
        let pipe_name = text("pipe")?.to_owned();
        if !matches!(
            pipe_name.as_str(),
            "GTA3_Pipe" | "GTA-VC_Pipe" | "GTA_SA_Pipe" | "GTAIV_Pipe" | "LANOIRE_Pipe"
        ) {
            return Err("invalid Rockstar launch pipe".into());
        }
        let material = text("material")?.as_bytes().to_vec();
        if material.len() != 640 || !material.iter().all(u8::is_ascii_hexdigit) {
            return Err("invalid launch material".into());
        }
        Some((pipe_name, material))
    } else {
        None
    };
    let session = config
        .get("session")
        .map(|value| -> Result<Session, Box<dyn std::error::Error>> {
            let account = value
                .get("accountId")
                .and_then(|v| v.as_str())
                .ok_or("missing SDK account")?
                .parse::<u64>()?;
            let identity = value
                .get("identity")
                .and_then(|v| v.as_str())
                .ok_or("missing SDK identity")?;
            let ticket = value
                .get("ticket")
                .and_then(|v| v.as_str())
                .ok_or("missing SDK ticket")?;
            let session = Session::new(account, identity.to_owned(), ticket.to_owned())?;
            Ok(if let Some(title) = value.get("titleId") {
                session.with_title(title.as_str().ok_or("invalid SDK title")?.to_owned())?
            } else {
                session
            })
        })
        .transpose()?;
    if legacy.is_none() && session.is_none() {
        return Err("missing launch protocol".into());
    }
    let root = std::fs::canonicalize(text("folder")?)?;
    let exe = std::fs::canonicalize(text("exe")?)?;
    let workdir = std::fs::canonicalize(text("workdir")?)?;
    if !exe.is_file()
        || !exe.starts_with(&root)
        || !workdir.starts_with(&root)
        || legacy.is_some()
            && !root.join("MTLX.dll").is_file()
            && !exe.parent().unwrap_or(&root).join("MTLX.dll").is_file()
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
    let legacy = legacy
        .map(|(name, material)| {
            let terminated = matches!(name.as_str(), "GTAIV_Pipe" | "LANOIRE_Pipe");
            Listener::new(&name, Duration::from_secs(90))
                .map(|listener| (listener, material, terminated))
        })
        .transpose()?;
    let instance = std::process::id();
    let sdk_listener = if session.is_some() {
        Some(Listener::new(
            &format!("chrome.rgsc_gameinst_{instance}"),
            Duration::from_secs(90),
        )?)
    } else {
        None
    };
    let mut identity_listeners = Vec::new();
    if session.is_some() {
        for name in
            droiddeck_rockstar_launcher::identity::pipe_names(&mut std::fs::File::open(&exe)?)?
        {
            identity_listeners.push(Listener::new(&name, Duration::from_secs(90))?);
        }
    }
    log("launcher pipes created");
    let mut command = std::process::Command::new(exe);
    command.current_dir(workdir).args(args?);
    if session.is_some() {
        command.arg(format!("-processInstance={instance}"));
    }
    let mut child = command.spawn()?;
    let pid = child.id();
    log("game process started");
    for listener in identity_listeners {
        std::thread::spawn(move || {
            let serve = || -> std::io::Result<()> {
                let mut pipe = listener.accept()?.with_deadline(Duration::from_secs(90));
                if pipe.client_pid()? != pid {
                    return Err(std::io::Error::new(
                        std::io::ErrorKind::PermissionDenied,
                        "unexpected launcher identity client",
                    ));
                }
                let reply = droiddeck_rockstar_launcher::identity::exchange(&mut pipe)?;
                pipe.write_all(&reply)?;
                log("launcher identity challenge complete");
                pipe = pipe.with_timeout(Duration::from_secs(86400));
                let mut progress = [0; 4096];
                while matches!(pipe.read(&mut progress), Ok(size) if size > 0) {}
                Ok(())
            };
            if let Err(error) = serve() {
                log(&format!("launcher identity exchange ended: {error}"));
            }
        });
    }
    let (result, handshake) = std::sync::mpsc::sync_channel(1);
    std::thread::spawn(move || {
        let serve = || -> std::io::Result<()> {
            if let Some((listener, material, terminated)) = legacy {
                let mut pipe = listener.accept()?;
                log("legacy launcher pipe connected");
                if pipe.client_pid()? != pid {
                    return Err(std::io::Error::new(
                        std::io::ErrorKind::PermissionDenied,
                        "unexpected Rockstar pipe client",
                    ));
                }
                let request = Frame::read(&mut pipe, Length::Total)?;
                if request.tag != *b"COLE" || !request.payload.is_empty() {
                    return Err(std::io::Error::new(
                        std::io::ErrorKind::InvalidData,
                        "unexpected Rockstar greeting",
                    ));
                }
                Frame {
                    tag: *b"DUNN",
                    payload: material,
                    nul_terminated: terminated,
                }
                .write(
                    &mut pipe,
                    if terminated {
                        Length::Total
                    } else {
                        Length::Body
                    },
                )?;
                log("legacy launcher handshake complete");
            }
            if let (Some(listener), Some(session)) = (sdk_listener, session) {
                let mut pipe = listener.accept()?.with_deadline(Duration::from_secs(90));
                if pipe.client_pid()? != pid {
                    return Err(std::io::Error::new(
                        std::io::ErrorKind::PermissionDenied,
                        "unexpected Rockstar SDK client",
                    ));
                }
                Message::hello(instance).write(&mut pipe)?;
                let mut authenticated = false;
                loop {
                    let request = match Message::read(&mut pipe) {
                        Ok(request) => request,
                        Err(_) if authenticated => return Ok(()),
                        Err(error) => return Err(error),
                    };
                    let responses = session.respond(&request)?;
                    let sends_login = request.kind == 0x40000;
                    for response in responses {
                        response.write(&mut pipe)?;
                    }
                    if sends_login && !authenticated {
                        authenticated = true;
                        pipe = pipe.with_timeout(Duration::from_secs(86400));
                        log("SDK login sent");
                        let _ = result.send(Ok(()));
                    }
                }
            }
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
