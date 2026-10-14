use std::fs;
use std::path::{Path, PathBuf};

use serde_json::Value;

pub const FUEL_FILE: &str = "fuel.json";
const MAX_DEPTH: usize = 16;
const STUB_BYTES: u64 = 1_000_000;
const STUB_WORDS: &[&str] = &["crash", "handler", "viewer", "compiler", "tool", "setup", "unins", "eac", "launcher", "steam"];
const SHIPPING_SUFFIXES: &[&str] = &["-win32.exe", "-win64.exe", "-win32-shipping.exe", "-win64-shipping.exe"];
const UNREAL_DIRS: &[&str] = &["/binaries/win32/", "/binaries/win64/"];

#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct FuelMain {
    pub command: String,
    pub args: Vec<String>,
}

pub fn parse_fuel(text: &str) -> Option<FuelMain> {
    let v: Value = serde_json::from_str(text.trim_start_matches('\u{feff}')).ok()?;
    let main = v.get("Main").filter(|m| m.is_object())?;
    let command = main.get("Command").and_then(|c| c.as_str()).unwrap_or("").trim().to_string();
    let args = main
        .get("Args")
        .and_then(|a| a.as_array())
        .map(|a| a.iter().filter_map(|x| x.as_str()).filter(|s| !s.is_empty()).map(str::to_string).collect())
        .unwrap_or_default();
    Some(FuelMain { command, args })
}

fn relative_command(command: &str) -> Option<String> {
    let mut path = command.replace('\\', "/");
    while let Some(rest) = path.strip_prefix("./") {
        path = rest.to_string();
    }
    let path = path.trim_start_matches('/').to_string();
    let ok = !path.is_empty() && !path.contains(':') && path.split('/').all(|s| !s.is_empty() && s != "." && s != "..");
    ok.then_some(path)
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Candidate {
    pub path: String,
    pub size: u64,
}

fn file_name(path: &str) -> &str {
    path.rsplit('/').next().unwrap_or(path)
}

fn letter_digits_name(name_lower: &str) -> bool {
    let Some(stem) = name_lower.strip_suffix(".exe") else { return false };
    let b = stem.as_bytes();
    (2..=4).contains(&b.len()) && b[0].is_ascii_lowercase() && b[1..].iter().all(u8::is_ascii_digit)
}

pub fn likely_stub(c: &Candidate) -> bool {
    let name = file_name(&c.path).to_lowercase();
    letter_digits_name(&name) || c.size < STUB_BYTES || STUB_WORDS.iter().any(|w| name.contains(w))
}

fn letters(text: &str) -> String {
    text.to_lowercase().chars().filter(|c| c.is_ascii_lowercase()).collect()
}

pub fn score(c: &Candidate, title: &str) -> i64 {
    let rel = format!("/{}", c.path.to_lowercase());
    let name = file_name(&rel).to_string();
    let mut s = 50;
    if SHIPPING_SUFFIXES.iter().any(|x| rel.ends_with(x)) {
        s += 300;
    }
    if UNREAL_DIRS.iter().any(|x| rel.contains(x)) {
        s += 250;
    }
    let title_lower = title.trim().to_lowercase();
    let title_letters = letters(title);
    let stem_letters = letters(name.strip_suffix(".exe").unwrap_or(&name));
    let title_in_path = !title_lower.is_empty() && rel.contains(&title_lower);
    let same_start = title_letters.len() >= 5 && stem_letters.len() >= 5 && title_letters[..5] == stem_letters[..5];
    if title_in_path || same_start {
        s += 100;
    }
    if STUB_WORDS.iter().any(|w| rel.contains(w)) {
        s -= 150;
    }
    if letter_digits_name(&name) {
        s -= 200;
    }
    s
}

pub fn choose(candidates: &[Candidate], title: &str) -> Option<Candidate> {
    let real: Vec<&Candidate> = candidates.iter().filter(|c| !likely_stub(c)).collect();
    let pool: Vec<&Candidate> = if real.is_empty() { candidates.iter().collect() } else { real };
    pool.into_iter()
        .max_by(|a, b| {
            score(a, title)
                .cmp(&score(b, title))
                .then(a.size.cmp(&b.size))
                .then_with(|| b.path.cmp(&a.path))
        })
        .cloned()
}

fn collect(dir: &Path, rel: &str, depth: usize, out: &mut Vec<Candidate>) {
    if depth > MAX_DEPTH {
        return;
    }
    let Ok(entries) = fs::read_dir(dir) else { return };
    let mut entries: Vec<_> = entries.flatten().collect();
    entries.sort_by_key(|e| e.file_name());
    for entry in entries {
        let Ok(meta) = entry.metadata() else { continue };
        let name = entry.file_name().to_string_lossy().into_owned();
        let child = if rel.is_empty() { name.clone() } else { format!("{rel}/{name}") };
        if meta.file_type().is_symlink() {
            continue;
        } else if meta.is_dir() {
            collect(&entry.path(), &child, depth + 1, out);
        } else if meta.is_file() && name.to_lowercase().ends_with(".exe") {
            out.push(Candidate { path: child, size: meta.len() });
        }
    }
}

pub fn executables(game_dir: &Path) -> Vec<Candidate> {
    let mut out = Vec::new();
    collect(game_dir, "", 0, &mut out);
    out
}

#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct LaunchSpec {
    pub exe: String,
    pub args: Vec<String>,
    pub source: &'static str,
    pub warning: Option<String>,
}

fn fuel_path(game_dir: &Path) -> Option<PathBuf> {
    let exact = game_dir.join(FUEL_FILE);
    if exact.is_file() {
        return Some(exact);
    }
    fs::read_dir(game_dir)
        .ok()?
        .flatten()
        .find(|e| e.file_name().to_string_lossy().eq_ignore_ascii_case(FUEL_FILE) && e.path().is_file())
        .map(|e| e.path())
}

pub fn launch_spec(game_dir: &Path, title: &str) -> LaunchSpec {
    let mut spec = LaunchSpec::default();
    if let Some(path) = fuel_path(game_dir) {
        match fs::read_to_string(&path).ok().as_deref().and_then(parse_fuel) {
            Some(main) => {
                spec.args = main.args;
                if let Some(rel) = relative_command(&main.command) {
                    let target = game_dir.join(&rel);
                    let regular = fs::symlink_metadata(&target).map(|m| m.is_file()).unwrap_or(false);
                    if regular {
                        spec.exe = rel;
                        spec.source = "fuel";
                        return spec;
                    }
                }
            }
            None => spec.warning = Some("fuel.json could not be read; choosing the exe by name".into()),
        }
    }
    if let Some(best) = choose(&executables(game_dir), title) {
        spec.exe = best.path;
        spec.source = "score";
    }
    spec
}

pub fn fuel_environment(entitlement_id: &str, sku: &str, display_name: &str) -> Vec<(String, String)> {
    let name = display_name.trim();
    let player = if name.is_empty() || name == "Amazon Games" { "Player" } else { name };
    vec![
        ("FUEL_DIR".into(), r"C:\ProgramData\Amazon Games Services\Legacy".into()),
        ("AMAZON_GAMES_SDK_PATH".into(), r"C:\ProgramData\Amazon Games Services\AmazonGamesSDK".into()),
        ("AMAZON_GAMES_FUEL_ENTITLEMENT_ID".into(), entitlement_id.into()),
        ("AMAZON_GAMES_FUEL_PRODUCT_SKU".into(), sku.into()),
        ("AMAZON_GAMES_FUEL_DISPLAY_NAME".into(), player.into()),
    ]
}

#[cfg(test)]
mod tests {
    use super::*;

    const BIG: u64 = 50_000_000;

    fn c(path: &str, size: u64) -> Candidate {
        Candidate { path: path.into(), size }
    }

    #[test]
    fn fuel_json_main_block() {
        let f = parse_fuel(r#"{"Main":{"Command":"  Bin\\Game.exe ","Args":["-a","",3,"-b"]},"PostInstall":[]}"#).unwrap();
        assert_eq!(f.command, "Bin\\Game.exe");
        assert_eq!(f.args, vec!["-a", "-b"]);
        assert_eq!(parse_fuel(r#"{"Main":{}}"#).unwrap(), FuelMain::default());
        assert!(parse_fuel(r#"{"Main":"x"}"#).is_none());
        assert!(parse_fuel("not json").is_none());
        assert!(parse_fuel("\u{feff}{\"Main\":{\"Command\":\"a.exe\"}}").is_some());
        assert_eq!(relative_command("\\Bin\\Game.exe").as_deref(), Some("Bin/Game.exe"));
        assert_eq!(relative_command("./Game.exe").as_deref(), Some("Game.exe"));
        assert_eq!(relative_command("..\\Other\\x.exe"), None);
        assert_eq!(relative_command("C:\\x.exe"), None);
        assert_eq!(relative_command(""), None);
    }

    #[test]
    fn stubs() {
        assert!(likely_stub(&c("a1.exe", BIG)));
        assert!(likely_stub(&c("sub/X123.exe", BIG)));
        assert!(!likely_stub(&c("ab1.exe", BIG)));
        assert!(!likely_stub(&c("a1234.exe", BIG)));
        assert!(likely_stub(&c("Game.exe", 999_999)));
        assert!(likely_stub(&c("CrashReporter.exe", BIG)));
        assert!(likely_stub(&c("UnityCrashHandler64.exe", BIG)));
        assert!(!likely_stub(&c("Game.exe", BIG)));
    }

    #[test]
    fn unreal_shipping_binary_wins() {
        let list = [
            c("Game.exe", 300_000_000),
            c("MyGame/Binaries/Win64/MyGame-Win64-Shipping.exe", 80_000_000),
            c("Engine/Extras/Redist/en-us/UE4PrereqSetup_x64.exe", 40_000_000),
        ];
        assert_eq!(choose(&list, "My Game").unwrap().path, "MyGame/Binaries/Win64/MyGame-Win64-Shipping.exe");
    }

    #[test]
    fn scoring_uses_relative_paths() {
        let a = c("Dread Templar.exe", 10_000_000);
        let b = c("bin/engine.exe", 20_000_000);
        assert_eq!(score(&a, "Dread Templar"), 150);
        assert_eq!(score(&b, "Dread Templar"), 50);
        assert_eq!(choose(&[a.clone(), b.clone()], "Dread Templar").unwrap().path, "Dread Templar.exe");
        assert_eq!(score(&c("DreadT.exe", BIG), "Dread Templar"), 150);
        assert_eq!(score(&c("Binaries/Win64/x.exe", BIG), ""), 300);
        assert_eq!(score(&c("tools/a12.exe", BIG), "z"), 50 - 150 - 200);
    }

    #[test]
    fn ties_go_to_the_larger_file_and_stubs_are_a_last_resort() {
        let list = [c("one.exe", 5_000_000), c("two.exe", 9_000_000)];
        assert_eq!(choose(&list, "zzz").unwrap().path, "two.exe");
        let stubs = [c("setup.exe", 5_000_000), c("small.exe", 10)];
        assert_eq!(choose(&stubs, "zzz").unwrap().path, "small.exe");
        assert_eq!(choose(&[], "zzz"), None);
    }

    #[test]
    fn spec_from_a_folder() {
        let dir = std::env::temp_dir().join(format!("dd-amazon-launch-{}", std::process::id()));
        let _ = fs::remove_dir_all(&dir);
        fs::create_dir_all(dir.join("Bin")).unwrap();
        fs::write(dir.join("Bin/Game.exe"), vec![0u8; 1_100_000]).unwrap();
        fs::write(dir.join("Other.exe"), vec![0u8; 2_000_000]).unwrap();
        fs::write(dir.join("fuel.json"), r#"{"Main":{"Command":"Bin\\Game.exe","Args":["-x"]}}"#).unwrap();
        let s = launch_spec(&dir, "Something");
        assert_eq!((s.exe.as_str(), s.source), ("Bin/Game.exe", "fuel"));
        assert_eq!(s.args, vec!["-x"]);
        fs::write(dir.join("fuel.json"), r#"{"Main":{"Command":"Missing.exe","Args":["-y"]}}"#).unwrap();
        let s = launch_spec(&dir, "Something");
        assert_eq!((s.exe.as_str(), s.source), ("Other.exe", "score"));
        assert_eq!(s.args, vec!["-y"]);
        fs::write(dir.join("fuel.json"), "{").unwrap();
        let s = launch_spec(&dir, "Something");
        assert!(s.args.is_empty() && s.warning.is_some());
        assert_eq!(s.exe, "Other.exe");
        let empty = std::env::temp_dir().join(format!("dd-amazon-launch-empty-{}", std::process::id()));
        fs::create_dir_all(&empty).unwrap();
        assert_eq!(launch_spec(&empty, "x").exe, "");
        let _ = fs::remove_dir_all(&dir);
        let _ = fs::remove_dir_all(&empty);
    }

    #[test]
    fn environment() {
        let env = fuel_environment("ent", "sku", "Amazon Games");
        let names: Vec<&str> = env.iter().map(|(k, _)| k.as_str()).collect();
        assert_eq!(
            names,
            vec![
                "FUEL_DIR",
                "AMAZON_GAMES_SDK_PATH",
                "AMAZON_GAMES_FUEL_ENTITLEMENT_ID",
                "AMAZON_GAMES_FUEL_PRODUCT_SKU",
                "AMAZON_GAMES_FUEL_DISPLAY_NAME"
            ]
        );
        assert_eq!(env[0].1, "C:\\ProgramData\\Amazon Games Services\\Legacy");
        assert_eq!(env[4].1, "Player");
        assert_eq!(fuel_environment("e", "", " ")[4].1, "Player");
        assert_eq!(fuel_environment("e", "", "Sam")[4].1, "Sam");
        assert_eq!(fuel_environment("e", "", "Sam")[2].1, "e");
    }
}
