use std::io::{BufRead, BufReader, Write};
use std::net::{TcpListener, TcpStream};
use std::sync::atomic::{AtomicBool, AtomicUsize};
use std::sync::{Arc, Mutex};

use flate2::write::DeflateEncoder;
use flate2::Compression;

use super::*;

struct Built {
    bytes: Vec<u8>,
}

fn build_zip(files: &[(&str, Vec<u8>, bool)]) -> Built {
    let mut out = Vec::new();
    let mut central = Vec::new();
    for (name, data, deflate) in files {
        let mut crc = Crc::new();
        crc.update(data);
        let body = if *deflate {
            let mut enc = DeflateEncoder::new(Vec::new(), Compression::default());
            enc.write_all(data).unwrap();
            enc.finish().unwrap()
        } else {
            data.clone()
        };
        let method: u16 = if *deflate { 8 } else { 0 };
        let offset = out.len() as u32;
        let extra = [0xAAu8, 0xBB, 2, 0, 1, 2];
        out.extend_from_slice(&0x0403_4b50u32.to_le_bytes());
        out.extend_from_slice(&[20, 0, 0, 0]);
        out.extend_from_slice(&method.to_le_bytes());
        out.extend_from_slice(&[0, 0, 0, 0]);
        out.extend_from_slice(&crc.sum().to_le_bytes());
        out.extend_from_slice(&(body.len() as u32).to_le_bytes());
        out.extend_from_slice(&(data.len() as u32).to_le_bytes());
        out.extend_from_slice(&(name.len() as u16).to_le_bytes());
        out.extend_from_slice(&(extra.len() as u16).to_le_bytes());
        out.extend_from_slice(name.as_bytes());
        out.extend_from_slice(&extra);
        out.extend_from_slice(&body);

        central.extend_from_slice(&0x0201_4b50u32.to_le_bytes());
        central.extend_from_slice(&[20, 0, 20, 0, 0, 0]);
        central.extend_from_slice(&method.to_le_bytes());
        central.extend_from_slice(&[0, 0, 0, 0]);
        central.extend_from_slice(&crc.sum().to_le_bytes());
        central.extend_from_slice(&(body.len() as u32).to_le_bytes());
        central.extend_from_slice(&(data.len() as u32).to_le_bytes());
        central.extend_from_slice(&(name.len() as u16).to_le_bytes());
        central.extend_from_slice(&[0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0]);
        central.extend_from_slice(&offset.to_le_bytes());
        central.extend_from_slice(name.as_bytes());
    }
    let cd_offset = out.len() as u32;
    let count = files.len() as u16;
    out.extend_from_slice(&central);
    out.extend_from_slice(&0x0605_4b50u32.to_le_bytes());
    out.extend_from_slice(&[0, 0, 0, 0]);
    out.extend_from_slice(&count.to_le_bytes());
    out.extend_from_slice(&count.to_le_bytes());
    out.extend_from_slice(&(central.len() as u32).to_le_bytes());
    out.extend_from_slice(&cd_offset.to_le_bytes());
    out.extend_from_slice(&[0, 0]);
    Built { bytes: out }
}

struct Server {
    url: String,
    requests: Arc<AtomicUsize>,
}

fn serve(body: Vec<u8>) -> Server {
    let listener = TcpListener::bind("127.0.0.1:0").unwrap();
    let addr = listener.local_addr().unwrap();
    let body = Arc::new(body);
    let requests = Arc::new(AtomicUsize::new(0));
    let counter = Arc::clone(&requests);
    std::thread::spawn(move || {
        for stream in listener.incoming() {
            let Ok(stream) = stream else { continue };
            let body = Arc::clone(&body);
            counter.fetch_add(1, Ordering::SeqCst);
            std::thread::spawn(move || answer(stream, &body));
        }
    });
    Server { url: format!("http://{addr}/build/game.zip?sig=abc"), requests }
}

fn answer(mut stream: TcpStream, body: &[u8]) {
    let mut reader = BufReader::new(stream.try_clone().unwrap());
    let mut first = String::new();
    if reader.read_line(&mut first).is_err() {
        return;
    }
    let mut range = None;
    loop {
        let mut line = String::new();
        match reader.read_line(&mut line) {
            Ok(0) | Err(_) => break,
            Ok(_) if line == "\r\n" => break,
            Ok(_) => {
                if let Some(v) = line.to_ascii_lowercase().strip_prefix("range: bytes=") {
                    range = Some(v.trim().to_string());
                }
            }
        }
    }
    let total = body.len() as u64;
    let Some(range) = range else {
        let _ = stream.write_all(format!("HTTP/1.1 200 OK\r\nContent-Length: {total}\r\nConnection: close\r\n\r\n").as_bytes());
        let _ = stream.write_all(body);
        return;
    };
    let (a, b) = range.split_once('-').unwrap();
    let (start, end) = if a.is_empty() {
        let n: u64 = b.parse().unwrap();
        (total.saturating_sub(n), total - 1)
    } else {
        let start: u64 = a.parse().unwrap();
        let end = if b.is_empty() { total - 1 } else { b.parse::<u64>().unwrap().min(total - 1) };
        (start, end)
    };
    let slice = &body[start as usize..=end as usize];
    let head = format!(
        "HTTP/1.1 206 Partial Content\r\nContent-Length: {}\r\nContent-Range: bytes {start}-{end}/{total}\r\nConnection: close\r\n\r\n",
        slice.len()
    );
    let _ = stream.write_all(head.as_bytes());
    let _ = stream.write_all(slice);
}

#[derive(Default)]
struct Recorder {
    logs: Mutex<Vec<String>>,
    gates: AtomicUsize,
    last: Mutex<(u64, u64)>,
}

impl Events for Recorder {
    fn log(&self, line: &str) {
        self.logs.lock().unwrap().push(line.to_string());
    }
    fn status(&self, _text: &str) {}
    fn checking(&self, _d: u64, _t: u64, _f: u32, _ft: u32) {}
    fn sizes(&self, _pending: u64, _total: u64) {}
    fn progress(&self, done: u64, total: u64, _files: u32, _files_total: u32) {
        *self.last.lock().unwrap() = (done, total);
    }
    fn before_replace(&self) -> bool {
        self.gates.fetch_add(1, Ordering::SeqCst);
        true
    }
}

fn sample() -> Vec<(&'static str, Vec<u8>, bool)> {
    let mut seed = 0x1234_5678u32;
    let big: Vec<u8> = (0..900_000u32).map(|_| { seed = seed.wrapping_mul(1_103_515_245).wrapping_add(12_345); (seed >> 16) as u8 }).collect();
    vec![
        ("__Installer/installerdata.xml", b"<DiPManifest version=\"4.0\"></DiPManifest>".to_vec(), true),
        ("Game.exe", big, true),
        ("Data\\stored.bin", vec![9u8; 4096], false),
        ("Data/", Vec::new(), false),
        ("empty.txt", Vec::new(), false),
    ]
}

fn request(url: &str, dir: &Path) -> Request {
    Request { url: url.to_string(), game_dir: dir.to_path_buf(), max_workers: 4, process_workers: 2, ca_bundle: String::new(), part_bytes: 64 * 1024 }
}

fn temp_dir(name: &str) -> PathBuf {
    let dir = std::env::temp_dir().join(format!("droiddeck-ea-{name}-{}", std::process::id()));
    let _ = fs::remove_dir_all(&dir);
    fs::create_dir_all(&dir).unwrap();
    dir
}

#[test]
fn installs_every_file_and_keeps_them_on_the_next_run() {
    let files = sample();
    let zip = build_zip(&files);
    let server = serve(zip.bytes);
    let dir = temp_dir("install");
    let ev = Recorder::default();
    let done = run(&request(&server.url, &dir), &AtomicBool::new(false), &ev).unwrap();
    assert_eq!(done.file_count, 4);
    assert_eq!(fs::read(dir.join("Game.exe")).unwrap(), files[1].1);
    assert_eq!(fs::read(dir.join("Data/stored.bin")).unwrap(), vec![9u8; 4096]);
    assert!(dir.join("empty.txt").is_file());
    assert!(dir.join("__Installer/installerdata.xml").is_file());
    assert!(!dir.join(IN_PROGRESS_MARKER).exists());
    let (shown, total) = *ev.last.lock().unwrap();
    assert_eq!(shown, total);

    let before = server.requests.load(Ordering::SeqCst);
    let again = run(&request(&server.url, &dir), &AtomicBool::new(false), &Recorder::default()).unwrap();
    assert_eq!(again.files_written, 0);
    assert!(server.requests.load(Ordering::SeqCst) - before <= 2);
    let _ = fs::remove_dir_all(&dir);
}

#[test]
fn an_update_replaces_changed_files_and_drops_removed_ones() {
    let dir = temp_dir("update");
    let first = serve(build_zip(&sample()).bytes);
    run(&request(&first.url, &dir), &AtomicBool::new(false), &Recorder::default()).unwrap();

    let mut next = sample();
    next[1].1[10] ^= 0xFF;
    next.retain(|f| f.0 != "Data\\stored.bin");
    let second = serve(build_zip(&next).bytes);
    let ev = Recorder::default();
    let done = run(&request(&second.url, &dir), &AtomicBool::new(false), &ev).unwrap();
    assert_eq!(done.files_written, 1);
    assert_eq!(done.removed, 1);
    assert_eq!(fs::read(dir.join("Game.exe")).unwrap(), next[1].1);
    assert!(!dir.join("Data/stored.bin").exists());
    assert_eq!(ev.gates.load(Ordering::SeqCst), 1);
    let _ = fs::remove_dir_all(&dir);
}

#[test]
fn a_damaged_file_on_disk_is_downloaded_again() {
    let dir = temp_dir("repair");
    let server = serve(build_zip(&sample()).bytes);
    run(&request(&server.url, &dir), &AtomicBool::new(false), &Recorder::default()).unwrap();
    let mut data = fs::read(dir.join("Game.exe")).unwrap();
    data[5] ^= 1;
    fs::write(dir.join("Game.exe"), &data).unwrap();
    let done = run(&request(&server.url, &dir), &AtomicBool::new(false), &Recorder::default()).unwrap();
    assert_eq!(done.files_written, 1);
    assert_eq!(fs::read(dir.join("Game.exe")).unwrap(), sample()[1].1);
    let _ = fs::remove_dir_all(&dir);
}

#[test]
fn reports_the_archive_size() {
    let server = serve(build_zip(&sample()).bytes);
    let answer = size(&server.url).unwrap();
    assert_eq!(answer["fileCount"], 4);
    let expected: usize = sample().iter().map(|f| f.1.len()).sum();
    assert_eq!(answer["totalBytes"], expected as u64);
}

#[test]
fn large_records_arrive_in_parallel_parts() {
    let dir = temp_dir("parts");
    let server = serve(build_zip(&sample()).bytes);
    let before = server.requests.load(Ordering::SeqCst);
    let done = run(&request(&server.url, &dir), &AtomicBool::new(false), &Recorder::default()).unwrap();
    assert_eq!(done.files_written, 4);
    assert!(server.requests.load(Ordering::SeqCst) - before > 6);
    assert_eq!(fs::read(dir.join("Game.exe")).unwrap(), sample()[1].1);
    assert!(fs::read_dir(&dir).unwrap().all(|e| !e.unwrap().file_name().to_string_lossy().ends_with(".ddzip")));
    let _ = fs::remove_dir_all(&dir);
}
