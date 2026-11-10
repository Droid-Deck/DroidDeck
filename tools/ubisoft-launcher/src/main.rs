//! Stands in for Ubisoft Connect on the local pipes a Ubisoft game talks to, and starts the game.
//!
//! Usage: droiddeck-upc.exe <launch.json>

#![cfg_attr(windows, windows_subsystem = "windows")]

mod launcher;
mod pb;
#[cfg(windows)]
mod win;

#[cfg(not(windows))]
fn main() {
    eprintln!("droiddeck-upc runs inside Wine");
    std::process::exit(2);
}

#[cfg(windows)]
fn main() {
    std::process::exit(service::run());
}

#[cfg(windows)]
mod service {
    use crate::launcher::{self, AuxReq, Config, SessionReq};
    use crate::win::{self, Handle};
    use std::fs::OpenOptions;
    use std::io::Write;
    use std::path::PathBuf;
    use std::sync::atomic::{AtomicU32, Ordering};
    use std::sync::{Arc, Mutex};
    use std::thread;
    use std::time::{Duration, Instant};

    const MAX_FRAME: usize = 16 << 20;
    const ANNOUNCE_WAIT: Duration = Duration::from_secs(120);
    const RESTART_WAIT: Duration = Duration::from_secs(15);
    const DENUVO_WAIT: Duration = Duration::from_secs(90);

    struct State {
        cfg: Config,
        prefix: String,
        log: Mutex<Option<std::fs::File>>,
        event: Handle,
        game: AtomicU32,
        started: Instant,
    }

    impl State {
        fn say(&self, line: &str) {
            let text = format!("[{:7.2}] {line}\n", self.started.elapsed().as_secs_f32());
            eprint!("droiddeck-upc: {text}");
            if let Some(f) = self.log.lock().unwrap().as_mut() {
                let _ = f.write_all(text.as_bytes());
            }
        }
    }

    struct Frames<'a> {
        pipe: &'a Handle,
        buf: Vec<u8>,
    }

    impl<'a> Frames<'a> {
        fn new(pipe: &'a Handle) -> Self {
            Frames { pipe, buf: Vec::new() }
        }

        fn fill(&mut self) -> bool {
            let mut chunk = vec![0u8; 65536];
            match self.pipe.read(&mut chunk) {
                Some(n) => {
                    self.buf.extend_from_slice(&chunk[..n]);
                    true
                }
                None => false,
            }
        }

        /// The next message without its 4-byte big-endian length.
        fn next(&mut self) -> Option<Vec<u8>> {
            while self.buf.len() < 4 {
                if !self.fill() {
                    return None;
                }
            }
            let n = u32::from_be_bytes(self.buf[..4].try_into().unwrap()) as usize;
            if n > MAX_FRAME {
                return None;
            }
            while self.buf.len() < 4 + n {
                if !self.fill() {
                    return None;
                }
            }
            let body = self.buf[4..4 + n].to_vec();
            self.buf.drain(..4 + n);
            Some(body)
        }
    }

    fn send(pipe: &Handle, body: &[u8]) -> bool {
        let mut framed = (body.len() as u32).to_be_bytes().to_vec();
        framed.extend_from_slice(body);
        pipe.write_all(&framed)
    }

    fn hold(state: &State, name: &str, frames: &mut Frames) {
        while let Some(body) = frames.next() {
            state.say(&format!("{name}: {} B left unanswered", body.len()));
        }
    }

    fn game_start(state: &State, pipe: Handle) {
        let mut frames = Frames::new(&pipe);
        let Some(req) = frames.next() else { return };
        send(&pipe, &state.cfg.game_start(&req));
        state.say("game_start: start granted");
        hold(state, "game_start", &mut frames);
    }

    fn api_process(state: &State, pipe: Handle) {
        let mut frames = Frames::new(&pipe);
        let Some(req) = frames.next() else { return };
        match launcher::announced(&req) {
            Some((product, pid)) => {
                state.say(&format!("api_process: product {product} is process {pid}"));
                if pid != 0 {
                    state.game.store(pid, Ordering::SeqCst);
                }
            }
            None => state.say(&format!("api_process: {} B announcement not understood", req.len())),
        }
        send(&pipe, &state.cfg.init_process());
        state.event.set();
        hold(state, "api_process", &mut frames);
    }

    fn session(state: &State, pipe: Handle) {
        let mut frames = Frames::new(&pipe);
        let mut pushes = 0;
        while let Some(req) = frames.next() {
            match launcher::session_request(&req) {
                SessionReq::Init => {
                    pushes += 1;
                    send(&pipe, &state.cfg.session());
                    state.say(&format!("session: init answered ({pushes})"));
                }
                SessionReq::Installer => {
                    send(&pipe, &state.cfg.installer());
                    state.say("session: installer init answered");
                }
                SessionReq::ProductList(id) => {
                    send(&pipe, &state.cfg.product_list(id));
                    state.say(&format!("session: product list {id} answered"));
                }
                SessionReq::Other => match launcher::acknowledge(&req) {
                    Some((answer, what)) => {
                        send(&pipe, &answer);
                        state.say(&format!("session: {what}"));
                    }
                    None => state.say(&format!("session: {} B request {} not handled", req.len(), hex(&req, 24))),
                },
            }
        }
        state.say("session: closed");
    }

    fn denuvo(state: &State, rid: u64, challenge: &[u8]) -> Option<Vec<u8>> {
        if state.cfg.bridge.is_empty() {
            return None;
        }
        let dir = PathBuf::from(&state.cfg.bridge);
        let _ = std::fs::create_dir_all(&dir);
        let rsp = dir.join(format!("denuvo-{rid}.rsp"));
        let _ = std::fs::remove_file(&rsp);
        let part = dir.join(format!("denuvo-{rid}.req.part"));
        std::fs::write(&part, challenge).ok()?;
        std::fs::rename(&part, dir.join(format!("denuvo-{rid}.req"))).ok()?;
        let deadline = Instant::now() + DENUVO_WAIT;
        while Instant::now() < deadline {
            if let Ok(token) = std::fs::read(&rsp) {
                let _ = std::fs::remove_file(&rsp);
                return (!token.is_empty()).then_some(token);
            }
            thread::sleep(Duration::from_millis(200));
        }
        None
    }

    fn aux(state: &State, pipe: Handle) {
        let mut frames = Frames::new(&pipe);
        while let Some(req) = frames.next() {
            match launcher::aux_request(&req) {
                AuxReq::Init(rid) => {
                    send(&pipe, &launcher::aux_init(rid, win::pid()));
                    state.say("aux: init answered");
                }
                AuxReq::Activate(rid, challenge) => {
                    state.say(&format!("aux: activation {rid} with a {} B challenge", challenge.len()));
                    match denuvo(state, rid, challenge) {
                        Some(token) => {
                            send(&pipe, &launcher::aux_token(rid, &token));
                            state.say(&format!("aux: activation {rid} answered ({} B)", token.len()));
                        }
                        None => state.say(&format!("aux: no game token for activation {rid}")),
                    }
                }
                AuxReq::Other(rid) => state.say(&format!("aux: request {rid} not handled ({} B)", req.len())),
            }
        }
    }

    fn service(state: &State, pipe: Handle) {
        send(&pipe, &state.cfg.service_hello());
        let mut chunk = vec![0u8; 65536];
        while pipe.read(&mut chunk).is_some() {}
    }

    fn quiet(state: &State, name: &str, pipe: Handle) {
        state.say(&format!("{name}: connected"));
        let mut chunk = vec![0u8; 65536];
        while let Some(n) = pipe.read(&mut chunk) {
            state.say(&format!("{name}: {n} B unanswered {}", hex(&chunk[..n], 64)));
        }
    }

    fn serve(state: Arc<State>, name: &'static str) {
        thread::spawn(move || {
            let full = format!("{}{name}", state.prefix);
            loop {
                let Some(pipe) = win::accept(&full) else {
                    state.say(&format!("{name}: cannot serve the pipe (error {})", win::last_error()));
                    return;
                };
                let s = state.clone();
                thread::spawn(move || match name {
                    launcher::GAME_START => game_start(&s, pipe),
                    launcher::API_PROCESS => api_process(&s, pipe),
                    launcher::SESSION => session(&s, pipe),
                    launcher::AUX => aux(&s, pipe),
                    launcher::SERVICE => service(&s, pipe),
                    _ => quiet(&s, name, pipe),
                });
            }
        });
    }

    fn hex(b: &[u8], n: usize) -> String {
        b.iter().take(n).map(|x| format!("{x:02x}")).collect()
    }

    fn command_line(cfg: &Config) -> String {
        let mut c = format!("\"{}\"", cfg.exe);
        if !cfg.args.trim().is_empty() {
            c.push(' ');
            c.push_str(cfg.args.trim());
        }
        c
    }

    pub fn run() -> i32 {
        let Some(path) = std::env::args().nth(1) else {
            eprintln!("usage: droiddeck-upc <launch.json>");
            return 2;
        };
        let parsed = std::fs::read_to_string(&path).map_err(|e| e.to_string()).and_then(|t| Config::parse(&t));
        let _ = std::fs::remove_file(&path);
        let cfg = match parsed {
            Ok(c) => c,
            Err(e) => {
                eprintln!("droiddeck-upc: {e}");
                return 2;
            }
        };
        let log = (!cfg.log.is_empty()).then(|| OpenOptions::new().create(true).append(true).open(&cfg.log).ok()).flatten();
        let state = Arc::new(State {
            prefix: format!(r"\\.\pipe\terminal_{}_", win::session_id()),
            cfg,
            log: Mutex::new(log),
            event: win::inheritable_event(),
            game: AtomicU32::new(0),
            started: Instant::now(),
        });
        state.say(&format!("product {} on {}*", state.cfg.product, state.prefix));
        for name in [launcher::GAME_START, launcher::API_PROCESS, launcher::SESSION, launcher::AUX, launcher::SERVICE]
            .into_iter()
            .chain(launcher::QUIET.iter().copied())
        {
            serve(state.clone(), name);
        }
        thread::sleep(Duration::from_millis(300));

        let (child, pid) = match win::spawn(&command_line(&state.cfg), &state.cfg.workdir) {
            Ok(c) => c,
            Err(e) => {
                state.say(&format!("cannot start {} (error {e})", state.cfg.exe));
                return 1;
            }
        };
        state.say(&format!("started {} as process {pid}", state.cfg.exe));
        while !child.wait(1000) {}
        let mut code = child.exit_code();
        state.say(&format!("process {pid} exited with 0x{code:08x}"));

        let mut waiting = Instant::now();
        let mut limit = ANNOUNCE_WAIT;
        loop {
            let game = state.game.swap(0, Ordering::SeqCst);
            if game != 0 {
                match win::open_process(game) {
                    Some(h) => {
                        while !h.wait(1000) {}
                        code = h.exit_code();
                        state.say(&format!("game process {game} exited with 0x{code:08x}"));
                    }
                    None => state.say(&format!("game process {game} is already gone")),
                }
                waiting = Instant::now();
                limit = RESTART_WAIT;
                continue;
            }
            if waiting.elapsed() >= limit {
                break;
            }
            thread::sleep(Duration::from_millis(500));
        }
        state.say("done");
        code as i32
    }
}
