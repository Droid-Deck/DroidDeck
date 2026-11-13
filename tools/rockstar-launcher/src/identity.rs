use std::collections::BTreeSet;
use std::fs::{self, File};
use std::io::{self, Read};
use std::path::Path;

const LIMIT: usize = 4096;

fn invalid() -> io::Error {
    io::Error::new(
        io::ErrorKind::InvalidData,
        "invalid launcher identity request",
    )
}

fn collect(bytes: &[u8], names: &mut BTreeSet<String>) {
    let prefix = br"\\.\pipe\";
    for (index, window) in bytes.windows(prefix.len()).enumerate() {
        if window != prefix {
            continue;
        }
        let tail = &bytes[index + prefix.len()..];
        let Some(end) = tail.iter().take(97).position(|byte| *byte == 0) else {
            continue;
        };
        let name = &tail[..end];
        if !name.is_empty()
            && name
                .iter()
                .all(|byte| byte.is_ascii_alphanumeric() || b"_-".contains(byte))
        {
            let name = std::str::from_utf8(name).unwrap();
            if name.ends_with("LauncherPipe") || name.ends_with("Launcher_Pipe") {
                names.insert(name.to_owned());
            }
        }
    }
}

pub fn title_pipe_names(exe: &Path) -> io::Result<Vec<String>> {
    let mut names = BTreeSet::new();
    scan(&mut File::open(exe)?, &mut names)?;
    let Some(folder) = exe.parent() else {
        return Ok(names.into_iter().collect());
    };
    let mut siblings = Vec::new();
    for entry in fs::read_dir(folder)? {
        let entry = entry?;
        let path = entry.path();
        if entry.file_type()?.is_file()
            && path != exe
            && path
                .extension()
                .is_some_and(|extension| extension.eq_ignore_ascii_case("exe"))
        {
            siblings.push(path);
        }
    }
    if siblings.len() > 32 {
        return Err(invalid());
    }
    siblings.sort();
    for path in siblings {
        scan(&mut File::open(path)?, &mut names)?;
    }
    Ok(names.into_iter().collect())
}

fn scan(reader: &mut impl Read, names: &mut BTreeSet<String>) -> io::Result<()> {
    let mut carry = Vec::new();
    let mut chunk = [0; 65536];
    let mut total = 0usize;
    loop {
        let count = reader.read(&mut chunk)?;
        if count == 0 {
            break;
        }
        total += count;
        if total > 256 * 1024 * 1024 {
            return Err(invalid());
        }
        carry.extend_from_slice(&chunk[..count]);
        collect(&carry, names);
        for offset in 0..2 {
            let ascii: Vec<_> = carry[offset..]
                .chunks_exact(2)
                .map(|pair| if pair[1] == 0 { pair[0] } else { 0 })
                .collect();
            collect(&ascii, names);
        }
        if names.len() > 4 {
            return Err(invalid());
        }
        carry.drain(..carry.len().saturating_sub(256));
    }
    Ok(())
}

pub fn exchange(reader: &mut impl Read) -> io::Result<Vec<u8>> {
    let mut header = [0; 4];
    reader.read_exact(&mut header)?;
    let framed = header != *b"INIT";
    let mut request = if framed {
        let size = u32::from_le_bytes(header) as usize;
        if !(6..=LIMIT).contains(&size) {
            return Err(invalid());
        }
        let mut bytes = vec![0; size];
        reader.read_exact(&mut bytes)?;
        bytes
    } else {
        let mut bytes = header.to_vec();
        let mut byte = [0];
        reader.read_exact(&mut byte)?;
        if byte[0] != b':' {
            return Err(invalid());
        }
        bytes.push(b':');
        loop {
            reader.read_exact(&mut byte)?;
            if !(32..127).contains(&byte[0]) || bytes.len() >= 261 {
                return Err(invalid());
            }
            bytes.push(byte[0]);
            if byte[0] == b':' {
                break;
            }
        }
        bytes
    };
    if request.last() == Some(&0) {
        request.pop();
    }
    let challenge = request.strip_prefix(b"INIT:").ok_or_else(invalid)?;
    if challenge.is_empty()
        || !framed && challenge == b":"
        || challenge.len() > 256
        || !challenge.iter().all(|byte| (32..127).contains(byte))
    {
        return Err(invalid());
    }
    let mut reply = b"TINI:launcher:".to_vec();
    reply.extend_from_slice(challenge);
    if framed {
        let mut bytes = (reply.len() as u32).to_le_bytes().to_vec();
        bytes.extend_from_slice(&reply);
        Ok(bytes)
    } else {
        Ok(reply)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Cursor;

    #[test]
    fn discovers_ascii_and_wide_pipe_names_across_chunks() {
        let mut bytes = vec![0; 65530];
        bytes.extend_from_slice(br"\\.\pipe\GameLauncherPipe");
        bytes.push(0);
        for value in "\\\\.\\pipe\\OtherLauncher_Pipe\0".encode_utf16() {
            bytes.extend_from_slice(&value.to_le_bytes());
        }
        bytes.extend_from_slice(br"\\.\pipe\..\outsideLauncherPipe");
        bytes.push(0);
        let mut names = BTreeSet::new();
        scan(&mut Cursor::new(bytes), &mut names).unwrap();
        assert_eq!(
            names.into_iter().collect::<Vec<_>>(),
            ["GameLauncherPipe", "OtherLauncher_Pipe"]
        );
    }

    fn folder(name: &str) -> std::path::PathBuf {
        let path =
            std::env::temp_dir().join(format!("droiddeck-identity-{name}-{}", std::process::id()));
        let _ = fs::remove_dir_all(&path);
        fs::create_dir(&path).unwrap();
        path
    }

    #[test]
    fn title_names_include_sibling_launcher_executables() {
        let root = folder("siblings");
        let game = root.join("Game.exe");
        fs::write(&game, b"MZ obfuscated").unwrap();
        fs::write(
            root.join("TitleLauncher.exe"),
            [br"MZ \\.\pipe\TitleLauncher_Pipe".as_slice(), b"\0"].concat(),
        )
        .unwrap();
        fs::write(
            root.join("Data.dat"),
            [br"\\.\pipe\DataLauncher_Pipe".as_slice(), b"\0"].concat(),
        )
        .unwrap();
        fs::create_dir(root.join("Folder.exe")).unwrap();
        let names = title_pipe_names(&game).unwrap();
        fs::remove_dir_all(&root).unwrap();
        assert_eq!(names, ["TitleLauncher_Pipe"]);
    }

    #[test]
    fn title_names_keep_the_game_executable_names_and_limits() {
        let root = folder("principal");
        let game = root.join("Game.exe");
        fs::write(
            &game,
            [br"MZ \\.\pipe\GameLauncherPipe".as_slice(), b"\0"].concat(),
        )
        .unwrap();
        fs::write(root.join("Tool.exe"), b"MZ plain").unwrap();
        assert_eq!(title_pipe_names(&game).unwrap(), ["GameLauncherPipe"]);
        for index in 0..32 {
            fs::write(root.join(format!("Extra{index}.exe")), b"MZ").unwrap();
        }
        let crowded = title_pipe_names(&game);
        fs::remove_dir_all(&root).unwrap();
        assert!(crowded.is_err());
    }

    #[test]
    fn identity_replies_echo_only_a_valid_challenge() {
        struct Split<R>(R);
        impl<R: Read> Read for Split<R> {
            fn read(&mut self, buffer: &mut [u8]) -> io::Result<usize> {
                let size = buffer.len().min(2);
                self.0.read(&mut buffer[..size])
            }
        }
        assert_eq!(
            exchange(&mut Split(Cursor::new(b"INIT:123456789:\0"))).unwrap(),
            b"TINI:launcher:123456789:"
        );
        let request = b"INIT:nonce:123:";
        let mut bytes = (request.len() as u32).to_le_bytes().to_vec();
        bytes.extend_from_slice(request);
        let reply = exchange(&mut Cursor::new(bytes)).unwrap();
        assert_eq!(
            u32::from_le_bytes(reply[..4].try_into().unwrap()) as usize,
            reply.len() - 4
        );
        assert_eq!(&reply[4..], b"TINI:launcher:nonce:123:");
        assert!(exchange(&mut Cursor::new(b"INIT:")).is_err());
        assert!(exchange(&mut Cursor::new(b"INIT:a\0b")).is_err());
        assert!(exchange(&mut Cursor::new(b"INIT:unterminated")).is_err());
        assert!(exchange(&mut Cursor::new([255; 4])).is_err());
    }
}
