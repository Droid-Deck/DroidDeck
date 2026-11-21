#[cfg(windows)]
fn main() {
    use std::ffi::OsString;
    use std::os::windows::ffi::OsStringExt;
    use std::os::windows::process::CommandExt;

    #[link(name = "kernel32")]
    extern "system" {
        fn GetCommandLineW() -> *const u16;
    }

    let line = unsafe {
        let start = GetCommandLineW();
        let mut len = 0;
        while *start.add(len) != 0 {
            len += 1;
        }
        std::slice::from_raw_parts(start, len)
    };
    let Some((exe, rest)) = droiddeck_epic_launcher::split(line) else {
        eprintln!("usage: EpicGamesLauncher.exe <game.exe> [arguments]");
        std::process::exit(2);
    };
    let exe = OsString::from_wide(&exe);
    let mut command = std::process::Command::new(&exe);
    if !rest.is_empty() {
        command.raw_arg(OsString::from_wide(rest));
    }
    match command.status() {
        Ok(status) => std::process::exit(status.code().unwrap_or(1)),
        Err(error) => {
            eprintln!("EpicGamesLauncher: could not start {}: {error}", exe.to_string_lossy());
            std::process::exit(1);
        }
    }
}

#[cfg(not(windows))]
fn main() {
    eprintln!("EpicGamesLauncher.exe runs on Windows or Wine");
    std::process::exit(1);
}
