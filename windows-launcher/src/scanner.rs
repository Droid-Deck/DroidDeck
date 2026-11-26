use crate::model::{Game, normalize_title, stable_key};
use regex::Regex;
use std::{
    collections::BTreeSet,
    fs,
    path::{Path, PathBuf},
};

pub fn merge_local(mut games: Vec<Game>) -> Vec<Game> {
    games.extend(scan_installed());
    games = crate::model::merge_games(games);
    let shortcuts = scan_shortcuts();
    for game in &mut games {
        if game.installed {
            continue;
        }
        if let Some(shortcut) = shortcuts
            .iter()
            .find(|shortcut| normalize_title(&shortcut.title) == normalize_title(&game.title))
        {
            game.installed = true;
            game.local_target.clone_from(&shortcut.local_target);
        }
    }
    games
}

fn scan_installed() -> Vec<Game> {
    let mut games = scan_steam();
    games.extend(scan_epic());
    games
}

fn scan_steam() -> Vec<Game> {
    let Some(root) = steam_root() else {
        return Vec::new();
    };
    let mut libraries = BTreeSet::new();
    libraries.insert(root.clone());
    let folders = root.join("steamapps/libraryfolders.vdf");
    if let Ok(text) = fs::read_to_string(folders) {
        if let Ok(path_pattern) = Regex::new(r#""path"\s*"((?:\\.|[^"])*)""#) {
            for capture in path_pattern.captures_iter(&text) {
                let value = decode_vdf(&capture[1]);
                let path = PathBuf::from(value);
                if path.join("steamapps").is_dir() {
                    libraries.insert(path);
                }
            }
        }
    }
    let app_pattern = Regex::new(r#""appid"\s*"(\d+)""#).unwrap();
    let name_pattern = Regex::new(r#""name"\s*"((?:\\.|[^"])*)""#).unwrap();
    let mut games = Vec::new();
    for library in libraries {
        let dir = library.join("steamapps");
        let Ok(entries) = fs::read_dir(dir) else {
            continue;
        };
        for entry in entries.flatten() {
            let name = entry.file_name().to_string_lossy().into_owned();
            if !name.to_ascii_lowercase().starts_with("appmanifest_")
                || !name.to_ascii_lowercase().ends_with(".acf")
            {
                continue;
            }
            let Ok(text) = fs::read_to_string(entry.path()) else {
                continue;
            };
            let Some(id) = app_pattern.captures(&text).map(|c| c[1].to_owned()) else {
                continue;
            };
            let Some(title) = name_pattern.captures(&text).map(|c| decode_vdf(&c[1])) else {
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
                ..Default::default()
            };
            game.normalize();
            games.push(game);
        }
    }
    games
}

fn scan_epic() -> Vec<Game> {
    let Some(base) = std::env::var_os("ProgramData").map(PathBuf::from) else {
        return Vec::new();
    };
    let manifests = base.join("Epic/EpicGamesLauncher/Data/Manifests");
    let Ok(entries) = fs::read_dir(manifests) else {
        return Vec::new();
    };
    let mut games = Vec::new();
    for entry in entries.flatten() {
        if entry.path().extension().and_then(|v| v.to_str()) != Some("item") {
            continue;
        }
        let Ok(text) = fs::read_to_string(entry.path()) else {
            continue;
        };
        let Ok(item) = serde_json::from_str::<serde_json::Value>(&text) else {
            continue;
        };
        if item["bIsIncompleteInstall"].as_bool().unwrap_or(false)
            || item["bIsApplication"].as_bool() == Some(false)
        {
            continue;
        }
        let app = item["AppName"].as_str().unwrap_or_default();
        let title = item["DisplayName"]
            .as_str()
            .filter(|s| !s.is_empty())
            .unwrap_or(app);
        if app.is_empty() || title.is_empty() {
            continue;
        }
        let id = item["CatalogItemId"]
            .as_str()
            .filter(|s| !s.is_empty())
            .unwrap_or(app);
        let namespace = item["CatalogNamespace"].as_str().unwrap_or_default();
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
            cover_url: if namespace.is_empty() {
                String::new()
            } else {
                format!("https://cdn1.epicgames.com/{namespace}/{id}/product?resize=600w")
            },
            ..Default::default()
        };
        game.normalize();
        games.push(game);
    }
    games
}

fn scan_shortcuts() -> Vec<Game> {
    let mut games = Vec::new();
    for root in [
        std::env::var_os("APPDATA")
            .map(PathBuf::from)
            .map(|p| p.join("Microsoft/Windows/Start Menu/Programs")),
        std::env::var_os("ProgramData")
            .map(PathBuf::from)
            .map(|p| p.join("Microsoft/Windows/Start Menu/Programs")),
    ]
    .into_iter()
    .flatten()
    {
        scan_links(&root, &mut games, 0);
    }
    games
}

fn scan_links(dir: &Path, games: &mut Vec<Game>, depth: usize) {
    if depth > 5 {
        return;
    }
    let Ok(entries) = fs::read_dir(dir) else {
        return;
    };
    for entry in entries.flatten() {
        let path = entry.path();
        if path.is_dir() {
            scan_links(&path, games, depth + 1);
            continue;
        }
        if !matches!(
            path.extension()
                .and_then(|v| v.to_str())
                .map(str::to_ascii_lowercase)
                .as_deref(),
            Some("lnk" | "url")
        ) {
            continue;
        }
        let title = path
            .file_stem()
            .and_then(|v| v.to_str())
            .unwrap_or_default();
        if title.is_empty() {
            continue;
        }
        let mut game = Game::manual(title, path.to_string_lossy().as_ref());
        game.key = stable_key("other", &path.to_string_lossy(), title);
        game.store_name = "Windows".into();
        game.local_target = path.to_string_lossy().into_owned();
        games.push(game);
    }
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
            .map(|p| p.join("Steam")),
        std::env::var_os("ProgramFiles")
            .map(PathBuf::from)
            .map(|p| p.join("Steam")),
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
        .map(|b| {
            if b.is_ascii_alphanumeric() || b"-_.~".contains(&b) {
                (b as char).to_string()
            } else {
                format!("%{b:02X}")
            }
        })
        .collect()
}
