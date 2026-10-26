use std::collections::HashMap;
use std::io::{BufRead, BufReader, Write};
use std::net::{TcpListener, TcpStream};
use std::sync::atomic::{AtomicBool, AtomicUsize, Ordering};
use std::sync::{Arc, Mutex};

use sha1::{Digest, Sha1};

use super::*;
use crate::epic::build_info::Cdn;
use crate::epic::chunk::testing::encode;
use crate::epic::manifest::testing::{build, chunk, TestFile, TestManifest};
use crate::epic::manifest::{guid_text, PartRef};

fn serve(files: HashMap<String, Vec<u8>>, hits: Arc<AtomicUsize>) -> String {
    let listener = TcpListener::bind("127.0.0.1:0").unwrap();
    let addr = listener.local_addr().unwrap();
    let files = Arc::new(files);
    std::thread::spawn(move || {
        for stream in listener.incoming() {
            let Ok(stream) = stream else { continue };
            let files = Arc::clone(&files);
            let hits = Arc::clone(&hits);
            std::thread::spawn(move || answer(stream, &files, &hits));
        }
    });
    format!("http://{addr}")
}

fn answer(mut stream: TcpStream, files: &HashMap<String, Vec<u8>>, hits: &AtomicUsize) {
    let mut reader = BufReader::new(stream.try_clone().unwrap());
    let mut first = String::new();
    if reader.read_line(&mut first).is_err() {
        return;
    }
    loop {
        let mut line = String::new();
        match reader.read_line(&mut line) {
            Ok(0) | Err(_) => break,
            Ok(_) if line == "\r\n" => break,
            Ok(_) => {}
        }
    }
    let path = first.split_whitespace().nth(1).unwrap_or("/").split('?').next().unwrap_or("/").to_string();
    hits.fetch_add(1, Ordering::Relaxed);
    let (status, body) = match files.get(&path) {
        Some(b) => ("200 OK", b.clone()),
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
    replaced: AtomicUsize,
    refuse: bool,
    last_progress: Mutex<(u64, u64)>,
    first_progress: Mutex<Option<(u64, u64)>>,
    sizes: Mutex<(u64, u64)>,
}

impl Events for Recorder {
    fn log(&self, line: &str) {
        self.logs.lock().unwrap().push(line.to_string());
    }
    fn status(&self, _text: &str) {}
    fn checking(&self, _d: u64, _t: u64, _f: u32, _ft: u32) {}
    fn sizes(&self, pending: u64, disk: u64) {
        *self.sizes.lock().unwrap() = (pending, disk);
    }
    fn progress(&self, written: u64, total: u64, _f: u32, _ft: u32) {
        self.first_progress.lock().unwrap().get_or_insert((written, total));
        let mut p = self.last_progress.lock().unwrap();
        if written >= p.0 {
            *p = (written, total);
        }
    }
    fn installing(&self, _d: u64, _t: u64, _f: u32, _ft: u32) {}
    fn before_replace(&self) -> bool {
        self.replaced.fetch_add(1, Ordering::SeqCst);
        !self.refuse
    }
}

struct Fixture {
    base: String,
    hits: Arc<AtomicUsize>,
    expected: Vec<(String, Vec<u8>)>,
}

fn pattern(seed: u8, len: usize) -> Vec<u8> {
    (0..len).map(|i| seed.wrapping_mul(31).wrapping_add((i * 7 % 251) as u8)).collect()
}

fn fixture() -> Fixture {
    let mut c1 = chunk(1, 4096);
    let mut c2 = chunk(2, 2048);
    let mut c3 = chunk(3, 1000);
    let d1 = pattern(1, 4096);
    let d2 = pattern(2, 2048);
    let d3 = pattern(3, 1000);
    c1.sha1 = Some(Sha1::digest(&d1).into());
    c2.sha1 = Some(Sha1::digest(&d2).into());
    c3.sha1 = Some(Sha1::digest(&d3).into());

    let a: Vec<u8> = [&d1[..3000], &d2[100..1100]].concat();
    let c: Vec<u8> = [&d1[3000..], &d3[..]].concat();
    let d: Vec<u8> = d2[..100].to_vec();
    let sha = |b: &[u8]| -> [u8; 20] { Sha1::digest(b).into() };
    let files = vec![
        TestFile {
            path: "a.bin".into(),
            sha1: sha(&a),
            tags: vec![],
            parts: vec![
                PartRef { guid: c1.guid, offset: 0, size: 3000 },
                PartRef { guid: c2.guid, offset: 100, size: 1000 },
            ],
        },
        TestFile {
            path: "b\\c.bin".into(),
            sha1: sha(&c),
            tags: vec!["English".into()],
            parts: vec![
                PartRef { guid: c1.guid, offset: 3000, size: 1096 },
                PartRef { guid: c3.guid, offset: 0, size: 1000 },
            ],
        },
        TestFile {
            path: "d.bin".into(),
            sha1: sha(&d),
            tags: vec![],
            parts: vec![PartRef { guid: c2.guid, offset: 0, size: 100 }],
        },
        TestFile { path: "e.txt".into(), sha1: [0; 20], tags: vec![], parts: vec![] },
        TestFile {
            path: "fr.txt".into(),
            sha1: [0; 20],
            tags: vec!["French".into()],
            parts: vec![PartRef { guid: c3.guid, offset: 0, size: 10 }],
        },
    ];
    let m = TestManifest {
        version: 21,
        build: "9.9".into(),
        exe: "a.bin".into(),
        prereq: Some(("Redist".into(), "redist\\setup.exe".into())),
        chunks: vec![c1.clone(), c2.clone(), c3.clone()],
        files,
        compress: true,
        meta_tail: vec![],
    };
    let manifest_bytes = build(&m);
    let parsed = crate::epic::manifest::parse(&manifest_bytes).unwrap();
    let mut served = HashMap::new();
    served.insert("/Builds/x/test.manifest".to_string(), manifest_bytes);
    for (info, data) in [(&c1, &d1), (&c2, &d2), (&c3, &d3)] {
        served.insert(format!("/Builds/x/{}", parsed.chunk_path(info)), encode(data, true));
    }
    let hits = Arc::new(AtomicUsize::new(0));
    let base = serve(served, Arc::clone(&hits));
    Fixture {
        base,
        hits,
        expected: vec![
            ("a.bin".into(), a),
            ("b/c.bin".into(), c),
            ("d.bin".into(), d),
            ("e.txt".into(), Vec::new()),
        ],
    }
}

fn request(fx: &Fixture, game: &Path) -> Request {
    Request {
        info: BuildInfo {
            cdns: vec![Cdn { base: fx.base.clone(), dir: "/Builds/x".into(), auth: String::new() }],
            manifest_file: "test.manifest".into(),
            build_version: "9.9".into(),
            deployment_id: "dep".into(),
        },
        game_dir: game.to_path_buf(),
        scratch_dir: None,
        storage_label: "Test storage".into(),
        tags: vec!["English".into(), "en-US".into(), "en".into()],
        max_workers: 4,
        process_workers: 2,
        ca_bundle: String::new(),
    }
}

fn temp_game(name: &str) -> PathBuf {
    let dir = std::env::temp_dir().join(format!("dd-epic-install-{name}-{}", std::process::id()));
    let _ = fs::remove_dir_all(&dir);
    dir
}

#[test]
fn fresh_install_then_rerun_then_repair() {
    let fx = fixture();
    let game = temp_game("e2e");
    let cancel = AtomicBool::new(false);

    let events = Recorder::default();
    let done = run(&request(&fx, &game), &cancel, &events).unwrap();
    for (path, bytes) in &fx.expected {
        assert_eq!(&fs::read(game.join(path)).unwrap(), bytes, "{path}");
    }
    assert!(!game.join("fr.txt").exists());
    assert_eq!(done.build_version, "9.9");
    assert_eq!(done.launch_exe, "a.bin");
    assert_eq!(done.prereq_path, "redist/setup.exe");
    assert!(done.prereq_read);
    assert_eq!(done.deployment_id, "dep");
    assert_eq!(done.disk_bytes, 4000 + 2096 + 100);
    assert_eq!(events.replaced.load(Ordering::SeqCst), 1);
    assert_eq!(*events.last_progress.lock().unwrap(), (6196, 6196));
    assert!(!game.join(BESIDE_CACHE_DIR).exists());
    assert!(!game.join(staging::DIR_NAME).exists());

    let hits_before = fx.hits.load(Ordering::Relaxed);
    let again = Recorder::default();
    run(&request(&fx, &game), &cancel, &again).unwrap();
    assert_eq!(fx.hits.load(Ordering::Relaxed) - hits_before, 1, "only the manifest is fetched");
    assert_eq!(again.sizes.lock().unwrap().0, 0);
    assert_eq!(again.replaced.load(Ordering::SeqCst), 1);

    fs::write(game.join("b/c.bin"), b"damaged").unwrap();
    let repair = Recorder::default();
    run(&request(&fx, &game), &cancel, &repair).unwrap();
    assert_eq!(fs::read(game.join("b/c.bin")).unwrap(), fx.expected[1].1);
    assert_eq!(repair.sizes.lock().unwrap().0, 2096 + 0, "the damaged file and the hashless empty file");
    let _ = fs::remove_dir_all(&game);
}

#[test]
fn a_refused_update_switch_replaces_nothing() {
    let fx = fixture();
    let game = temp_game("refuse");
    fs::create_dir_all(&game).unwrap();
    fs::write(game.join("a.bin"), b"old contents").unwrap();
    let cancel = AtomicBool::new(false);
    let events = Recorder { refuse: true, ..Default::default() };
    let err = run(&request(&fx, &game), &cancel, &events).unwrap_err();
    assert!(matches!(err, Stop::Failed(_)), "{err:?}");
    assert_eq!(fs::read(game.join("a.bin")).unwrap(), b"old contents");
    let stage = game.join(staging::DIR_NAME);
    let leftovers = fs::read_dir(&stage).map(|e| e.flatten().filter(|e| e.file_name() != "owner").count()).unwrap_or(0);
    assert_eq!(leftovers, 0);
    let _ = fs::remove_dir_all(&game);
}

#[test]
fn a_cancelled_run_reports_cancel() {
    let fx = fixture();
    let game = temp_game("cancel");
    let cancel = AtomicBool::new(true);
    let events = Recorder::default();
    assert_eq!(run(&request(&fx, &game), &cancel, &events).unwrap_err(), Stop::Cancelled);
    let _ = fs::remove_dir_all(&game);
}

fn place_partial(fx: &Fixture, game: &Path, data: &[u8], list: &str) -> PathBuf {
    let stage = staging::recover(game).unwrap();
    let a = &fx.expected[0].1;
    let partial = stage.join(staging::partial_name("a.bin", 4000, Some(Sha1::digest(a).into())));
    fs::write(&partial, data).unwrap();
    fs::write(staging::list_path(&partial), list).unwrap();
    partial
}

fn place_done_c(fx: &Fixture, game: &Path) {
    fs::create_dir_all(game.join("b")).unwrap();
    fs::write(game.join("b/c.bin"), &fx.expected[1].1).unwrap();
}

#[test]
fn a_paused_install_resumes_from_its_written_parts() {
    let fx = fixture();
    let game = temp_game("resume");
    place_done_c(&fx, &game);
    place_partial(&fx, &game, &fx.expected[0].1[..3000], "0\n");
    let stage = game.join(staging::DIR_NAME);
    fs::write(stage.join(staging::partial_name("gone.bin", 9, None)), b"old").unwrap();

    let events = Recorder::default();
    run(&request(&fx, &game), &AtomicBool::new(false), &events).unwrap();
    for (path, bytes) in &fx.expected {
        assert_eq!(&fs::read(game.join(path)).unwrap(), bytes, "{path}");
    }
    assert_eq!(fx.hits.load(Ordering::Relaxed), 2, "the manifest and the one chunk still missing");
    assert_eq!(*events.first_progress.lock().unwrap(), Some((2096 + 3000, 6196)));
    assert_eq!(*events.last_progress.lock().unwrap(), (6196, 6196));
    assert!(events.logs.lock().unwrap().iter().any(|l| l == "resuming a.bin with 3000 of 4000 bytes written"));
    assert!(!stage.exists());
    let _ = fs::remove_dir_all(&game);
}

#[test]
fn a_part_written_out_of_order_is_kept_too() {
    let fx = fixture();
    let game = temp_game("resume-tail");
    place_done_c(&fx, &game);
    let mut data = vec![0u8; 3000];
    data.extend_from_slice(&fx.expected[0].1[3000..]);
    place_partial(&fx, &game, &data, "3000\n");
    let events = Recorder::default();
    run(&request(&fx, &game), &AtomicBool::new(false), &events).unwrap();
    assert_eq!(fs::read(game.join("a.bin")).unwrap(), fx.expected[0].1);
    assert_eq!(*events.first_progress.lock().unwrap(), Some((2096 + 1000, 6196)));
    assert!(!game.join(staging::DIR_NAME).exists());
    let _ = fs::remove_dir_all(&game);
}

#[test]
fn a_partial_that_no_longer_matches_is_dropped() {
    let fx = fixture();
    let game = temp_game("badpartial");
    place_partial(&fx, &game, &fx.expected[0].1[..1234], "1234\n");
    let events = Recorder::default();
    run(&request(&fx, &game), &AtomicBool::new(false), &events).unwrap();
    assert_eq!(fs::read(game.join("a.bin")).unwrap(), fx.expected[0].1);
    assert_eq!(*events.first_progress.lock().unwrap(), Some((0, 6196)));
    assert!(!game.join(staging::DIR_NAME).exists());
    let _ = fs::remove_dir_all(&game);
}

#[test]
fn a_damaged_partial_fails_its_check_and_is_not_kept() {
    let fx = fixture();
    let game = temp_game("damagedpartial");
    place_partial(&fx, &game, &[0u8; 3000], "0\n");
    let err = run(&request(&fx, &game), &AtomicBool::new(false), &Recorder::default()).unwrap_err();
    assert_eq!(err, Stop::Failed("SHA-1 mismatch for a.bin".into()));
    assert!(staging::partials(&game.join(staging::DIR_NAME)).is_empty());
    let retry = Recorder::default();
    run(&request(&fx, &game), &AtomicBool::new(false), &retry).unwrap();
    assert_eq!(fs::read(game.join("a.bin")).unwrap(), fx.expected[0].1);
    let _ = fs::remove_dir_all(&game);
}

#[test]
fn guid_names_are_cache_names() {
    let c = chunk(5, 10);
    assert_eq!(cache::entry_name(&c, 0), guid_text(&c.guid));
    assert_eq!(cache::entry_name(&c, 2), format!("{}.2", guid_text(&c.guid)));
}
