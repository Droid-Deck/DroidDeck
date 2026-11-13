use std::collections::BTreeMap;
use std::fs::{self, File};
use std::io::{self, Read};
use std::path::{Path, PathBuf};

const LIMIT: usize = 4096;

fn invalid() -> io::Error {
    io::Error::new(
        io::ErrorKind::InvalidData,
        "invalid launcher identity request",
    )
}

type Names = BTreeMap<String, String>;

const SCAN_LIMIT: u64 = 256 * 1024 * 1024;
const NAME_LIMIT: usize = 4;
const SIBLING_LIMIT: usize = 32;

fn collect(bytes: &[u8], names: &mut Names) {
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
                names
                    .entry(name.to_ascii_lowercase())
                    .or_insert_with(|| name.to_owned());
            }
        }
    }
}

pub fn title_pipe_names(exe: &Path) -> io::Result<Vec<String>> {
    discover(exe, SCAN_LIMIT)
}

fn discover(exe: &Path, limit: u64) -> io::Result<Vec<String>> {
    let mut names = Names::new();
    let mut principal = limit;
    scan(&mut File::open(exe)?, &mut names, &mut principal)?;
    let mut extra = Names::new();
    let mut budget = limit;
    for path in siblings(exe) {
        if extra.len() >= NAME_LIMIT || budget == 0 {
            break;
        }
        let Ok(mut file) = File::open(&path) else {
            continue;
        };
        if !file
            .metadata()
            .is_ok_and(|metadata| metadata.len() <= budget)
        {
            continue;
        }
        let mut own = Names::new();
        if scan(&mut file, &mut own, &mut budget).is_err() {
            continue;
        }
        for (key, name) in own {
            if extra.len() < NAME_LIMIT && !names.contains_key(&key) {
                extra.entry(key).or_insert(name);
            }
        }
    }
    names.append(&mut extra);
    Ok(names.into_values().collect())
}

fn siblings(exe: &Path) -> Vec<PathBuf> {
    let Some(entries) = exe.parent().and_then(|folder| fs::read_dir(folder).ok()) else {
        return Vec::new();
    };
    let mut paths: Vec<_> = entries
        .flatten()
        .filter(|entry| entry.file_type().is_ok_and(|kind| kind.is_file()))
        .map(|entry| entry.path())
        .filter(|path| {
            path != exe
                && path
                    .extension()
                    .is_some_and(|extension| extension.eq_ignore_ascii_case("exe"))
        })
        .collect();
    paths.sort();
    paths.truncate(SIBLING_LIMIT);
    paths
}

fn scan(reader: &mut impl Read, names: &mut Names, budget: &mut u64) -> io::Result<()> {
    let mut carry = Vec::new();
    let mut chunk = [0; 65536];
    loop {
        let count = reader.read(&mut chunk)?;
        if count == 0 {
            break;
        }
        *budget = budget.checked_sub(count as u64).ok_or_else(invalid)?;
        carry.extend_from_slice(&chunk[..count]);
        collect(&carry, names);
        for offset in 0..2 {
            let ascii: Vec<_> = carry[offset..]
                .chunks_exact(2)
                .map(|pair| if pair[1] == 0 { pair[0] } else { 0 })
                .collect();
            collect(&ascii, names);
        }
        if names.len() > NAME_LIMIT {
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
        let mut names = Names::new();
        scan(&mut Cursor::new(bytes), &mut names, &mut SCAN_LIMIT.clone()).unwrap();
        assert_eq!(
            names.into_values().collect::<Vec<_>>(),
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

    fn executable(path: &Path, names: &[&str]) {
        let mut bytes = b"MZ ".to_vec();
        for name in names {
            bytes.extend_from_slice(br"\\.\pipe\");
            bytes.extend_from_slice(name.as_bytes());
            bytes.push(0);
        }
        fs::write(path, bytes).unwrap();
    }

    #[test]
    fn title_names_keep_the_game_executable_names_and_limits() {
        let root = folder("principal");
        let game = root.join("Game.exe");
        executable(&game, &["GameLauncherPipe"]);
        fs::write(root.join("Tool.exe"), b"MZ plain").unwrap();
        assert_eq!(title_pipe_names(&game).unwrap(), ["GameLauncherPipe"]);
        for index in 0..40 {
            fs::write(root.join(format!("Extra{index:02}.exe")), b"MZ").unwrap();
        }
        executable(&root.join("Zz.exe"), &["LateLauncher_Pipe"]);
        let crowded = title_pipe_names(&game);
        executable(
            &game,
            &[
                "ALauncherPipe",
                "BLauncherPipe",
                "CLauncherPipe",
                "DLauncherPipe",
                "ELauncherPipe",
            ],
        );
        let overfull = title_pipe_names(&game);
        fs::remove_dir_all(&root).unwrap();
        assert_eq!(crowded.unwrap(), ["GameLauncherPipe"]);
        assert!(overfull.is_err());
    }

    #[test]
    fn title_names_skip_siblings_that_cannot_be_scanned() {
        use std::os::unix::fs::PermissionsExt;
        let root = folder("unscannable");
        let game = root.join("Game.exe");
        executable(&game, &["GameLauncherPipe"]);
        let locked = root.join("Locked.exe");
        executable(&locked, &["LockedLauncher_Pipe"]);
        fs::set_permissions(&locked, fs::Permissions::from_mode(0o000)).unwrap();
        let locked_open = File::open(&locked).is_ok();
        File::create(root.join("Huge.exe"))
            .unwrap()
            .set_len(SCAN_LIMIT + 1)
            .unwrap();
        executable(
            &root.join("Noisy.exe"),
            &[
                "NALauncherPipe",
                "NBLauncherPipe",
                "NCLauncherPipe",
                "NDLauncherPipe",
                "NELauncherPipe",
            ],
        );
        let names = title_pipe_names(&game);
        fs::remove_dir_all(&root).unwrap();
        let mut expected = vec!["GameLauncherPipe".to_owned()];
        if locked_open {
            expected.push("LockedLauncher_Pipe".to_owned());
        }
        assert_eq!(names.unwrap(), expected);
    }

    #[test]
    fn title_names_bound_the_bytes_read_from_siblings() {
        let root = folder("budget");
        let game = root.join("Game.exe");
        executable(&game, &["GameLauncherPipe"]);
        executable(&root.join("First.exe"), &["FirstLauncherPipe"]);
        executable(&root.join("Second.exe"), &["SecondLauncherPipe"]);
        let size = fs::metadata(root.join("First.exe")).unwrap().len();
        let names = discover(&game, size + 8);
        fs::remove_dir_all(&root).unwrap();
        assert_eq!(names.unwrap(), ["FirstLauncherPipe", "GameLauncherPipe"]);
    }

    #[test]
    fn title_names_merge_case_insensitively_and_cap_sibling_names() {
        let root = folder("merge");
        let game = root.join("Game.exe");
        executable(
            &game,
            &[
                "ALauncherPipe",
                "BLauncherPipe",
                "CLauncherPipe",
                "GTAVLauncher_Pipe",
            ],
        );
        executable(
            &root.join("A.exe"),
            &["gtavlauncher_pipe", "ELauncherPipe", "FLauncherPipe"],
        );
        executable(
            &root.join("B.exe"),
            &["GLauncherPipe", "HLauncherPipe", "ILauncherPipe"],
        );
        let names = title_pipe_names(&game);
        fs::remove_dir_all(&root).unwrap();
        assert_eq!(
            names.unwrap(),
            [
                "ALauncherPipe",
                "BLauncherPipe",
                "CLauncherPipe",
                "ELauncherPipe",
                "FLauncherPipe",
                "GLauncherPipe",
                "GTAVLauncher_Pipe",
                "HLauncherPipe"
            ]
        );
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
