use std::collections::HashMap;
use std::io::{BufRead, BufReader, Write};
use std::net::{TcpListener, TcpStream};
use std::path::Path;
use std::sync::atomic::{AtomicBool, AtomicUsize, Ordering};
use std::sync::{Arc, Mutex};

use sha2::{Digest, Sha256};

use super::*;
use crate::amazon::codec::testing::frame;
use crate::amazon::manifest::testing::{message, Entry};

#[derive(Default)]
struct Seen {
    paths: Vec<String>,
    queries_ok: bool,
    manifest_token: Option<String>,
    file_tokens: usize,
    agents: Vec<String>,
}

struct Server {
    base: String,
    seen: Arc<Mutex<Seen>>,
    files: Arc<Mutex<HashMap<String, Vec<u8>>>>,
}

fn serve(files: HashMap<String, Vec<u8>>) -> Server {
    let listener = TcpListener::bind("127.0.0.1:0").unwrap();
    let addr = listener.local_addr().unwrap();
    let files = Arc::new(Mutex::new(files));
    let seen = Arc::new(Mutex::new(Seen { queries_ok: true, ..Default::default() }));
    let (f2, s2) = (Arc::clone(&files), Arc::clone(&seen));
    std::thread::spawn(move || {
        for stream in listener.incoming() {
            let Ok(stream) = stream else { continue };
            let (files, seen) = (Arc::clone(&f2), Arc::clone(&s2));
            std::thread::spawn(move || answer(stream, &files, &seen));
        }
    });
    Server { base: format!("http://{addr}/v/abc?sig=s%2Bt&e=1"), seen, files }
}

fn answer(mut stream: TcpStream, files: &Mutex<HashMap<String, Vec<u8>>>, seen: &Mutex<Seen>) {
    let mut reader = BufReader::new(stream.try_clone().unwrap());
    let mut first = String::new();
    if reader.read_line(&mut first).is_err() {
        return;
    }
    let mut token = None;
    let mut agent = String::new();
    loop {
        let mut line = String::new();
        match reader.read_line(&mut line) {
            Ok(0) | Err(_) => break,
            Ok(_) if line == "\r\n" => break,
            Ok(_) => {
                let lower = line.to_ascii_lowercase();
                if let Some(v) = lower.strip_prefix("x-amzn-token:") {
                    token = Some(v.trim().to_string());
                }
                if lower.starts_with("user-agent:") {
                    agent = line["user-agent:".len()..].trim().to_string();
                }
            }
        }
    }
    let target = first.split_whitespace().nth(1).unwrap_or("/").to_string();
    let (path, query) = target.split_once('?').unwrap_or((target.as_str(), ""));
    {
        let mut s = seen.lock().unwrap();
        s.paths.push(path.to_string());
        s.queries_ok &= query == "sig=s%2Bt&e=1";
        s.agents.push(agent);
        if path.ends_with("/manifest.proto") {
            s.manifest_token = token;
        } else if token.is_some() {
            s.file_tokens += 1;
        }
    }
    let body = files.lock().unwrap().get(path).cloned();
    let (status, body) = match body {
        Some(b) => ("200 OK", b),
        None => ("404 Not Found", Vec::new()),
    };
    let head = format!("HTTP/1.1 {status}\r\nContent-Length: {}\r\nConnection: close\r\n\r\n", body.len());
    let _ = stream.write_all(head.as_bytes());
    let _ = stream.write_all(&body);
    let _ = stream.flush();
}

#[derive(Default)]
struct Recorder {
    logs: Mutex<Vec<String>>,
    gate_calls: AtomicUsize,
    refuse: bool,
    last: Mutex<(u64, u64, u32, u32)>,
    backwards: AtomicBool,
    sizes: Mutex<(u64, u64)>,
}

impl Events for Recorder {
    fn log(&self, line: &str) {
        self.logs.lock().unwrap().push(line.to_string());
    }
    fn status(&self, _text: &str) {}
    fn checking(&self, _d: u64, _t: u64, _f: u32, _ft: u32) {}
    fn sizes(&self, pending: u64, total: u64) {
        *self.sizes.lock().unwrap() = (pending, total);
    }
    fn progress(&self, done: u64, total: u64, files: u32, files_total: u32) {
        let mut last = self.last.lock().unwrap();
        if done < last.0 {
            self.backwards.store(true, Ordering::SeqCst);
        }
        *last = (done, total, files, files_total);
    }
    fn before_replace(&self) -> bool {
        self.gate_calls.fetch_add(1, Ordering::SeqCst);
        !self.refuse
    }
}

fn pattern(seed: u8, len: usize) -> Vec<u8> {
    (0..len).map(|i| seed.wrapping_mul(29).wrapping_add((i * 13 % 251) as u8)).collect()
}

struct Fixture {
    server: Server,
    expected: Vec<(&'static str, Vec<u8>)>,
}

fn fixture_with(paths: &[(&'static str, Vec<u8>)]) -> Fixture {
    let digests: Vec<Vec<u8>> = paths.iter().map(|(_, b)| Sha256::digest(b).to_vec()).collect();
    let mut packages = vec![Vec::new(), Vec::new()];
    for (i, (path, bytes)) in paths.iter().enumerate() {
        let digest: &[u8] = if bytes.is_empty() { &[] } else { &digests[i] };
        packages[i % 2].push(Entry { path, size: bytes.len() as u64, algorithm: Some(0), digest });
    }
    let manifest = frame(&message(&packages), b"\x08\x01\x12\x00", true);
    let mut files = HashMap::new();
    files.insert("/v/abc/manifest.proto".to_string(), manifest);
    for (i, (_, bytes)) in paths.iter().enumerate() {
        files.insert(format!("/v/abc/files/{}", net::hex_lower(&digests[i])), bytes.clone());
    }
    Fixture { server: serve(files), expected: paths.iter().map(|(p, b)| (*p, b.clone())).collect() }
}

fn fixture() -> Fixture {
    fixture_with(&[
        ("Game.exe", pattern(1, 300_000)),
        ("Data\\pak0.pak", pattern(2, 70_000)),
        ("Data\\Sub\\readme.txt", b"hello".to_vec()),
        ("Data\\empty.cfg", Vec::new()),
        ("fuel.json", br#"{"Main":{"Command":"Game.exe","Args":["-w"]}}"#.to_vec()),
    ])
}

fn request(game: &Path) -> Request {
    Request {
        access_token: "token-1".into(),
        entitlement_id: "ent".into(),
        game_dir: game.to_path_buf(),
        max_workers: 4,
        process_workers: 2,
        ca_bundle: String::new(),
    }
}

fn download(fx: &Fixture) -> Download {
    Download { base_url: fx.server.base.clone(), version_id: "ver-9".into() }
}

fn temp_game(name: &str) -> PathBuf {
    let dir = std::env::temp_dir().join(format!("dd-amazon-install-{name}-{}", std::process::id()));
    let _ = fs::remove_dir_all(&dir);
    dir
}

fn requests(fx: &Fixture) -> usize {
    fx.server.seen.lock().unwrap().paths.len()
}

fn no_temps(dir: &Path) -> bool {
    fn walk(dir: &Path) -> bool {
        fs::read_dir(dir).unwrap().flatten().all(|e| {
            let p = e.path();
            if p.is_dir() {
                walk(&p)
            } else {
                !e.file_name().to_string_lossy().ends_with(".tmp")
            }
        })
    }
    walk(dir)
}

#[test]
fn fresh_install_then_rerun_then_repair() {
    let fx = fixture();
    let game = temp_game("e2e");
    let cancel = AtomicBool::new(false);

    let ev = Recorder::default();
    let done = run_download(&request(&game), &download(&fx), &cancel, &ev).unwrap();
    for (path, bytes) in &fx.expected {
        assert_eq!(&fs::read(game.join(path.replace('\\', "/"))).unwrap(), bytes, "{path}");
    }
    let total: u64 = fx.expected.iter().map(|(_, b)| b.len() as u64).sum();
    assert_eq!(done.version_id, "ver-9");
    assert_eq!(done.total_bytes, total);
    assert_eq!(done.file_count, 5);
    assert_eq!(done.files_written, 5);
    assert_eq!(ev.gate_calls.load(Ordering::SeqCst), 1);
    assert_eq!(*ev.last.lock().unwrap(), (total, total, 5, 5));
    assert!(!ev.backwards.load(Ordering::SeqCst));
    assert!(game.join(COMPLETE_MARKER).exists());
    assert!(!game.join(IN_PROGRESS_MARKER).exists());
    assert!(no_temps(&game));
    {
        let seen = fx.server.seen.lock().unwrap();
        assert_eq!(seen.paths.len(), 5, "{:?}", seen.paths);
        assert!(seen.queries_ok, "the signed query is kept on every URL");
        assert_eq!(seen.manifest_token.as_deref(), Some("token-1"));
        assert_eq!(seen.file_tokens, 0, "file requests carry no token");
        assert!(seen.agents.iter().all(|a| a.starts_with("droiddeck/") || a.starts_with("DroidDeck/")), "{:?}", seen.agents);
    }

    let before = requests(&fx);
    let again = Recorder::default();
    run_download(&request(&game), &download(&fx), &cancel, &again).unwrap();
    assert_eq!(requests(&fx) - before, 1);
    assert_eq!(again.sizes.lock().unwrap().0, 0);
    assert_eq!(again.gate_calls.load(Ordering::SeqCst), 0);

    fs::write(game.join("Data/pak0.pak"), vec![0u8; 70_000]).unwrap();
    fs::write(game.join("Game.exe.tmp"), b"junk").unwrap();
    fs::write(game.join("notes.txt"), b"not in the manifest").unwrap();
    let before = requests(&fx);
    let repair = Recorder::default();
    run_download(&request(&game), &download(&fx), &cancel, &repair).unwrap();
    assert_eq!(requests(&fx) - before, 2, "manifest + the damaged file");
    assert_eq!(fs::read(game.join("Data/pak0.pak")).unwrap(), fx.expected[1].1);
    assert_eq!(*repair.sizes.lock().unwrap(), (70_000, total));
    assert_eq!(repair.gate_calls.load(Ordering::SeqCst), 1);
    assert!(!game.join("Game.exe.tmp").exists());
    assert!(game.join("notes.txt").exists(), "files outside the manifest are left alone");
    let _ = fs::remove_dir_all(&game);
}

#[test]
fn a_corrupt_file_fails_the_run_and_leaves_no_temporary_files() {
    let fx = fixture();
    let digest = net::hex_lower(&Sha256::digest(&fx.expected[1].1));
    fx.server.files.lock().unwrap().insert(format!("/v/abc/files/{digest}"), vec![7u8; 70_000]);
    let game = temp_game("corrupt");
    let cancel = AtomicBool::new(false);
    let ev = Recorder::default();
    let err = run_download(&request(&game), &download(&fx), &cancel, &ev).unwrap_err();
    assert_eq!(err, Stop::Failed(FILE_FAILED.into()));
    assert!(!game.join("Data/pak0.pak").exists());
    assert!(no_temps(&game));
    assert!(game.join(IN_PROGRESS_MARKER).exists());
    assert!(ev.logs.lock().unwrap().iter().any(|l| l.contains("SHA-256 mismatch")));
    let _ = fs::remove_dir_all(&game);
}

#[test]
fn a_refused_switch_to_updating_replaces_nothing() {
    let fx = fixture();
    let game = temp_game("refuse");
    fs::create_dir_all(&game).unwrap();
    fs::write(game.join("Game.exe"), b"old").unwrap();
    let cancel = AtomicBool::new(false);
    let ev = Recorder { refuse: true, ..Default::default() };
    let err = run_download(&request(&game), &download(&fx), &cancel, &ev).unwrap_err();
    assert_eq!(err, Stop::Failed(REPLACE_REFUSED.into()));
    assert_eq!(fs::read(game.join("Game.exe")).unwrap(), b"old");
    assert!(no_temps(&game));
    let _ = fs::remove_dir_all(&game);
}

#[test]
fn cancel_and_bad_manifests() {
    let fx = fixture();
    let game = temp_game("cancel");
    let cancel = AtomicBool::new(true);
    assert_eq!(run_download(&request(&game), &download(&fx), &cancel, &Recorder::default()).unwrap_err(), Stop::Cancelled);

    let bad = fixture_with(&[("..\\escape.dll", b"x".to_vec())]);
    let cancel = AtomicBool::new(false);
    let err = run_download(&request(&game), &download(&bad), &cancel, &Recorder::default()).unwrap_err();
    match err {
        Stop::Failed(m) => assert!(m.contains("..\\escape.dll"), "{m}"),
        other => panic!("{other:?}"),
    }
    assert!(!game.parent().unwrap().join("escape.dll").exists());

    let missing = Download { base_url: format!("{}x", fx.server.base.split('?').next().unwrap()), version_id: String::new() };
    let err = run_download(&request(&game), &missing, &cancel, &Recorder::default()).unwrap_err();
    assert_eq!(err, Stop::Failed(NO_MANIFEST.into()));
    let _ = fs::remove_dir_all(&game);
}

#[test]
fn requests_from_json() {
    let ok = Request::from_json(&json!({"accessToken":"t","entitlementId":"e","gameDir":"/g","maxWorkers":16})).unwrap();
    assert_eq!((ok.max_workers, ok.process_workers), (16, 2));
    assert!(Request::from_json(&json!({"accessToken":"t","entitlementId":"e","gameDir":"rel"})).is_err());
    assert_eq!(Request::from_json(&json!({"entitlementId":"e","gameDir":"/g"})).err().unwrap(), NOT_SIGNED_IN);
    assert!(Request::from_json(&json!({"accessToken":"t","gameDir":"/g"})).is_err());
}
