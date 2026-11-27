use crate::model::{Game, stable_key};
use regex::Regex;
use std::{
    collections::{BTreeSet, HashSet},
    fs,
    io::ErrorKind,
    path::{Path, PathBuf},
};

#[derive(Default)]
pub struct ScanResult {
    pub games: Vec<Game>,
    pub issues: Vec<String>,
}

pub fn merge_local(games: Vec<Game>) -> Vec<Game> {
    merge_local_with_report(games).games
}

pub fn merge_local_with_report(mut games: Vec<Game>) -> ScanResult {
    let mut issues = Vec::new();
    let scanned = scan_installed(&mut issues);
    let shortcuts = scan_shortcuts(&mut issues);
    let scanned_keys: HashSet<String> = scanned.iter().map(|game| game.key.clone()).collect();
    if issues.is_empty() {
        for game in &mut games {
            if matches!(game.store.as_str(), "steam" | "epic") && game.imported_from != "manual" {
                game.installed = scanned_keys.contains(&game.key);
                if !game.installed {
                    game.local_target.clear();
                }
            }
        }
        games.retain(|game| game.imported_from != "local-scan" || scanned_keys.contains(&game.key));
    }
    games.extend(scanned);
    games.extend(shortcuts);
    ScanResult {
        games: crate::model::merge_games(games),
        issues,
    }
}

fn scan_installed(issues: &mut Vec<String>) -> Vec<Game> {
    let mut games = scan_steam(issues);
    games.extend(scan_epic(issues));
    games
}

fn scan_steam(issues: &mut Vec<String>) -> Vec<Game> {
    let Some(root) = steam_root() else {
        return Vec::new();
    };
    let mut libraries = BTreeSet::new();
    libraries.insert(root.clone());
    let folders = root.join("steamapps/libraryfolders.vdf");
    match fs::read_to_string(&folders) {
        Ok(text) => {
            if let Ok(path_pattern) = Regex::new(r#""path"\s*"((?:\\.|[^"])*)""#) {
                for capture in path_pattern.captures_iter(&text) {
                    let value = decode_vdf(&capture[1]);
                    let path = PathBuf::from(value);
                    if scan_directory(&path.join("steamapps"), "Steam library", issues) {
                        libraries.insert(path);
                    }
                }
            }
        }
        Err(error) => record_scan_error("Steam library folders", &folders, &error, issues),
    }
    let app_pattern = Regex::new(r#""appid"\s*"(\d+)""#).unwrap();
    let name_pattern = Regex::new(r#""name"\s*"((?:\\.|[^"])*)""#).unwrap();
    let state_pattern = Regex::new(r#""StateFlags"\s*"(\d+)""#).unwrap();
    let mut games = Vec::new();
    for library in libraries {
        let dir = library.join("steamapps");
        let entries = match fs::read_dir(&dir) {
            Ok(entries) => entries,
            Err(error) => {
                record_scan_error("Steam manifests", &dir, &error, issues);
                continue;
            }
        };
        for entry in entries {
            let entry = match entry {
                Ok(entry) => entry,
                Err(error) => {
                    issues.push(format!("No se pudo leer una entrada de Steam: {error}"));
                    continue;
                }
            };
            let name = entry.file_name().to_string_lossy().into_owned();
            if !name.to_ascii_lowercase().starts_with("appmanifest_")
                || !name.to_ascii_lowercase().ends_with(".acf")
            {
                continue;
            }
            let path = entry.path();
            let text = match fs::read_to_string(&path) {
                Ok(text) => text,
                Err(error) => {
                    record_scan_error("Steam manifest", &path, &error, issues);
                    continue;
                }
            };
            let Some(state_flags) = state_pattern
                .captures(&text)
                .and_then(|captures| captures[1].parse::<u32>().ok())
            else {
                issues.push(format!(
                    "Steam manifest without valid StateFlags: {}",
                    path.display()
                ));
                continue;
            };
            if !steam_state_is_playable(state_flags) {
                continue;
            }
            let Some(id) = app_pattern
                .captures(&text)
                .map(|capture| capture[1].to_owned())
            else {
                issues.push(format!(
                    "Steam manifest without a valid app id: {}",
                    path.display()
                ));
                continue;
            };
            let Some(title) = name_pattern
                .captures(&text)
                .map(|capture| decode_vdf(&capture[1]))
            else {
                issues.push(format!(
                    "Steam manifest without a valid title: {}",
                    path.display()
                ));
                continue;
            };
            let mut game = Game {
                key: stable_key("steam", &id, &title),
                store: "steam".into(),
                store_name: "Steam".into(),
                store_id: id.clone(),
                app_id: id.clone(),
                title,
                owned: false,
                installed: true,
                local_target: format!("steam://run/{id}"),
                store_url: format!("https://store.steampowered.com/app/{id}/"),
                cover_url: format!(
                    "https://cdn.cloudflare.steamstatic.com/steam/apps/{id}/library_600x900.jpg"
                ),
                hero_url: format!(
                    "https://cdn.cloudflare.steamstatic.com/steam/apps/{id}/library_hero.jpg"
                ),
                imported_from: "local-scan".into(),
                ..Default::default()
            };
            game.normalize();
            games.push(game);
        }
    }
    games
}

fn scan_epic(issues: &mut Vec<String>) -> Vec<Game> {
    let Some(base) = std::env::var_os("ProgramData").map(PathBuf::from) else {
        return Vec::new();
    };
    let manifests = base.join("Epic/EpicGamesLauncher/Data/Manifests");
    let entries = match fs::read_dir(&manifests) {
        Ok(entries) => entries,
        Err(error) => {
            record_scan_error("Epic manifests", &manifests, &error, issues);
            return Vec::new();
        }
    };
    let mut games = Vec::new();
    for entry in entries {
        let entry = match entry {
            Ok(entry) => entry,
            Err(error) => {
                issues.push(format!("No se pudo leer una entrada de Epic: {error}"));
                continue;
            }
        };
        let path = entry.path();
        if path.extension().and_then(|value| value.to_str()) != Some("item") {
            continue;
        }
        let text = match fs::read_to_string(&path) {
            Ok(text) => text,
            Err(error) => {
                record_scan_error("Epic manifest", &path, &error, issues);
                continue;
            }
        };
        let item = match serde_json::from_str::<serde_json::Value>(&text) {
            Ok(item) => item,
            Err(error) => {
                issues.push(format!(
                    "No se pudo interpretar el manifiesto de Epic {}: {error}",
                    path.display()
                ));
                continue;
            }
        };
        if item["bIsIncompleteInstall"].as_bool().unwrap_or(false)
            || item["bIsApplication"].as_bool() == Some(false)
        {
            continue;
        }
        let app = item["AppName"].as_str().unwrap_or_default();
        let title = item["DisplayName"]
            .as_str()
            .filter(|value| !value.is_empty())
            .unwrap_or(app);
        if app.is_empty() || title.is_empty() {
            issues.push(format!(
                "El manifiesto de Epic no tiene nombre o id: {}",
                path.display()
            ));
            continue;
        }
        let namespace = item["CatalogNamespace"].as_str().unwrap_or_default();
        let id = item["CatalogItemId"]
            .as_str()
            .filter(|value| !value.is_empty())
            .unwrap_or(namespace);
        let mut game = Game {
            key: stable_key("epic", id, title),
            store: "epic".into(),
            store_name: "Epic Games".into(),
            store_id: id.into(),
            title: title.into(),
            owned: false,
            installed: true,
            local_target: format!(
                "com.epicgames.launcher://apps/{}?action=launch&silent=true",
                encode(app)
            ),
            store_url: format!(
                "https://store.epicgames.com/en-US/browse?q={}",
                encode(title)
            ),
            cover_url: if namespace.is_empty() || id.is_empty() {
                String::new()
            } else {
                format!("https://cdn1.epicgames.com/{namespace}/{id}/product?resize=600w")
            },
            imported_from: "local-scan".into(),
            ..Default::default()
        };
        game.normalize();
        games.push(game);
    }
    games
}

fn scan_shortcuts(issues: &mut Vec<String>) -> Vec<Game> {
    let mut games = Vec::new();
    for root in [
        std::env::var_os("APPDATA")
            .map(PathBuf::from)
            .map(|path| path.join("Microsoft/Windows/Start Menu/Programs")),
        std::env::var_os("ProgramData")
            .map(PathBuf::from)
            .map(|path| path.join("Microsoft/Windows/Start Menu/Programs")),
    ]
    .into_iter()
    .flatten()
    {
        scan_links(&root, &mut games, issues, 0);
    }
    games
}

fn scan_links(dir: &Path, games: &mut Vec<Game>, issues: &mut Vec<String>, depth: usize) {
    if depth > 5 {
        return;
    }
    let entries = match fs::read_dir(dir) {
        Ok(entries) => entries,
        Err(error) => {
            record_scan_error("accesos directos de Windows", dir, &error, issues);
            return;
        }
    };
    for entry in entries {
        let entry = match entry {
            Ok(entry) => entry,
            Err(error) => {
                issues.push(format!(
                    "No se pudo leer una entrada de accesos directos: {error}"
                ));
                continue;
            }
        };
        let path = entry.path();
        let file_type = match entry.file_type() {
            Ok(file_type) => file_type,
            Err(error) => {
                record_scan_error("acceso directo", &path, &error, issues);
                continue;
            }
        };
        if file_type.is_dir() {
            scan_links(&path, games, issues, depth + 1);
            continue;
        }
        if !matches!(
            path.extension()
                .and_then(|value| value.to_str())
                .map(str::to_ascii_lowercase)
                .as_deref(),
            Some("lnk" | "url")
        ) {
            continue;
        }
        let title = path
            .file_stem()
            .and_then(|value| value.to_str())
            .unwrap_or_default();
        if title.is_empty() {
            continue;
        }
        let mut game = Game::manual(title, path.to_string_lossy().as_ref());
        game.store_name = "Windows".into();
        game.local_target = path.to_string_lossy().into_owned();
        games.push(game);
    }
}

fn record_scan_error(label: &str, path: &Path, error: &std::io::Error, issues: &mut Vec<String>) {
    if error.kind() != ErrorKind::NotFound {
        issues.push(format!(
            "No se pudo leer {label} {}: {error}",
            path.display()
        ));
    }
}

fn scan_directory(path: &Path, label: &str, issues: &mut Vec<String>) -> bool {
    match fs::metadata(path) {
        Ok(metadata) if metadata.is_dir() => true,
        Ok(_) => {
            issues.push(format!("{label} no es una carpeta: {}", path.display()));
            false
        }
        Err(error) => {
            record_scan_error(label, path, &error, issues);
            false
        }
    }
}

fn steam_state_is_playable(state_flags: u32) -> bool {
    state_flags & 4 != 0
}

#[cfg(windows)]
fn steam_root() -> Option<PathBuf> {
    use winreg::{
        RegKey,
        enums::{HKEY_CURRENT_USER, HKEY_LOCAL_MACHINE},
    };
    for (key, name) in [
        (RegKey::predef(HKEY_CURRENT_USER), "Software\\Valve\\Steam"),
        (
            RegKey::predef(HKEY_LOCAL_MACHINE),
            "SOFTWARE\\WOW6432Node\\Valve\\Steam",
        ),
        (RegKey::predef(HKEY_LOCAL_MACHINE), "SOFTWARE\\Valve\\Steam"),
    ] {
        if let Ok(subkey) = key.open_subkey(name) {
            for value in ["SteamPath", "InstallPath"] {
                if let Ok(path) = subkey.get_value::<String, _>(value) {
                    let path = PathBuf::from(path.replace('/', "\\"));
                    if path.join("steamapps").is_dir() {
                        return Some(path);
                    }
                }
            }
        }
    }
    [
        std::env::var_os("ProgramFiles(x86)")
            .map(PathBuf::from)
            .map(|path| path.join("Steam")),
        std::env::var_os("ProgramFiles")
            .map(PathBuf::from)
            .map(|path| path.join("Steam")),
        Some(PathBuf::from("D:\\Steam")),
    ]
    .into_iter()
    .flatten()
    .find(|path| path.join("steamapps").is_dir())
}

#[cfg(not(windows))]
fn steam_root() -> Option<PathBuf> {
    None
}

fn decode_vdf(value: &str) -> String {
    value.replace("\\\\", "\\").replace("\\\"", "\"")
}

fn encode(value: &str) -> String {
    value
        .bytes()
        .map(|byte| {
            if byte.is_ascii_alphanumeric() || b"-_.~".contains(&byte) {
                (byte as char).to_string()
            } else {
                format!("%{byte:02X}")
            }
        })
        .collect()
}

#[cfg(test)]
mod tests {
    use super::steam_state_is_playable;

    #[test]
    fn steam_installation_requires_the_fully_installed_flag() {
        assert!(!steam_state_is_playable(0));
        assert!(!steam_state_is_playable(2));
        assert!(steam_state_is_playable(4));
        assert!(steam_state_is_playable(6));
    }
}
