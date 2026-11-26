//! The signed-in account's library: licenses → packages (PICS) → apps (PICS, with access
//! tokens). Only apps of type `game` are listed.

use crate::steam::pb::cmsg_client_pics::{PicsAppInfoReq, PicsPackageInfoReq};
use crate::steam::session::Session;
use crate::steam::vdf::{self, KVNode};
use serde_json::{json, Value};
use std::collections::{HashMap, HashSet};

const PACKAGE_BATCH: usize = 200;
const APP_BATCH: usize = 100;

#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct Game {
    pub app_id: u32,
    pub name: String,
    pub install_dir: String,
    pub app_type: String,
    pub free: bool,
}

#[derive(Clone, Debug, Default)]
pub struct Library {
    /// Every app id the account's licenses grant (games, DLC, tools …).
    pub owned_apps: HashSet<u32>,
    /// Every depot id the account's licenses grant; the client installs no other depot of an app.
    pub owned_depots: HashSet<u32>,
    games: Option<Vec<Game>>,
}

fn package_ids(buffer: &[u8], key: &str) -> Vec<u32> {
    let Some((_id, root)) = vdf::parse_binary_package(buffer) else {
        return Vec::new();
    };
    root.child(key)
        .map(|node| {
            node.children
                .iter()
                .map(|c| c.as_uint(0) as u32)
                .filter(|id| *id != 0)
                .collect()
        })
        .unwrap_or_default()
}

pub fn package_app_ids(buffer: &[u8]) -> Vec<u32> {
    package_ids(buffer, "appids")
}

pub fn package_depot_ids(buffer: &[u8]) -> Vec<u32> {
    package_ids(buffer, "depotids")
}

/// A library entry for an app, or `None` when it is not a game.
pub fn game_from_appinfo(app_id: u32, appinfo: &KVNode) -> Option<Game> {
    let info = crate::steam::plan::appinfo_root(appinfo);
    let app_type = crate::steam::plan::app_type(info);
    if !app_type.eq_ignore_ascii_case("game") {
        return None;
    }
    let name = crate::steam::plan::app_name(info);
    if name.is_empty() {
        return None;
    }
    Some(Game {
        app_id,
        name,
        install_dir: crate::steam::plan::app_install_dir(info),
        app_type: app_type.to_ascii_lowercase(),
        free: crate::steam::plan::is_free(info),
    })
}

pub fn sort_games(games: &mut [Game]) {
    games.sort_by(|a, b| {
        a.name
            .to_lowercase()
            .cmp(&b.name.to_lowercase())
            .then(a.app_id.cmp(&b.app_id))
    });
}

pub fn games_json(games: &[Game]) -> Value {
    json!({
        "ok": true,
        "games": games.iter().map(|g| json!({
            "appId": g.app_id,
            "name": g.name,
            "installDir": g.install_dir,
            "type": g.app_type,
            "free": g.free,
        })).collect::<Vec<_>>(),
    })
}

impl Library {
    /// Licenses → package PICS → owned app ids. Package 0 (the basic Steam license every account
    /// has) is skipped.
    pub fn load(session: &Session) -> Result<Library, String> {
        let licenses = session
            .core()
            .license_list()
            .ok_or_else(|| "no license list from Steam".to_string())?;
        let mut tokens: HashMap<u32, u64> = HashMap::new();
        for license in &licenses {
            if license.package_id == 0 {
                continue;
            }
            let entry = tokens.entry(license.package_id).or_insert(0);
            if license.access_token != 0 {
                *entry = license.access_token;
            }
        }
        let mut package_ids: Vec<u32> = tokens.keys().copied().collect();
        package_ids.sort_unstable();
        let mut owned_apps = HashSet::new();
        let mut owned_depots = HashSet::new();
        // A package the CM left out of its answer (neither info nor unknown) is asked once more;
        // ownership is not published while one is still unanswered.
        let mut pending = package_ids.clone();
        for attempt in 0..2 {
            let mut unanswered = Vec::new();
            for batch in pending.chunks(PACKAGE_BATCH) {
                let request = batch
                    .iter()
                    .map(|id| PicsPackageInfoReq {
                        packageid: *id,
                        access_token: tokens.get(id).copied().unwrap_or(0),
                    })
                    .collect();
                let response = session
                    .pics_product_info(request, Vec::new())
                    .ok_or_else(|| "package info request failed".to_string())?;
                let answered: HashSet<u32> = response
                    .packages
                    .iter()
                    .map(|p| p.packageid)
                    .chain(response.unknown_packageids.iter().copied())
                    .collect();
                for package in &response.packages {
                    owned_apps.extend(package_app_ids(&package.buffer));
                    owned_depots.extend(package_depot_ids(&package.buffer));
                }
                unanswered.extend(batch.iter().copied().filter(|id| !answered.contains(id)));
            }
            pending = unanswered;
            if pending.is_empty() {
                break;
            }
            if attempt == 1 {
                return Err(format!("package info incomplete ({} unanswered)", pending.len()));
            }
        }
        crate::steam::log(&format!(
            "library: licenses={} packages={} apps={}",
            licenses.len(),
            package_ids.len(),
            owned_apps.len()
        ));
        Ok(Library {
            owned_apps,
            owned_depots,
            games: None,
        })
    }

    pub fn cached_games(&self) -> Option<Vec<Game>> {
        self.games.clone()
    }

    pub fn owns(&self, app_id: u32) -> bool {
        self.owned_apps.contains(&app_id)
    }

    /// App PICS for every owned app (access tokens first), filtered to games, sorted by name.
    pub fn load_games(&self, session: &Session) -> Result<Vec<Game>, String> {
        if let Some(games) = &self.games {
            return Ok(games.clone());
        }
        let mut app_ids: Vec<u32> = self.owned_apps.iter().copied().collect();
        app_ids.sort_unstable();
        let mut tokens: HashMap<u32, u64> = HashMap::new();
        for batch in app_ids.chunks(APP_BATCH * 5) {
            if let Some(response) = session.pics_access_tokens(Vec::new(), batch.to_vec()) {
                for token in response.app_access_tokens {
                    tokens.insert(token.appid, token.access_token);
                }
            }
        }
        let mut games = Vec::new();
        // An app whose appinfo did not arrive (left out, or its HTTP delivery failed) is asked
        // once more; it is not taken for a non-game while its metadata is missing.
        let mut pending = app_ids;
        for attempt in 0..2 {
            let mut unresolved = Vec::new();
            for batch in pending.chunks(APP_BATCH) {
                let request = batch
                    .iter()
                    .map(|id| PicsAppInfoReq {
                        appid: *id,
                        access_token: tokens.get(id).copied().unwrap_or(0),
                        only_public_obsolete: false,
                    })
                    .collect();
                let response = session
                    .pics_product_info(Vec::new(), request)
                    .ok_or_else(|| "app info request failed".to_string())?;
                let mut settled: HashSet<u32> = response.unknown_appids.iter().copied().collect();
                for app in &response.apps {
                    if app.buffer.is_empty() {
                        if app.missing_token {
                            settled.insert(app.appid);
                        }
                        continue;
                    }
                    settled.insert(app.appid);
                    let Some(root) = vdf::parse_auto(&app.buffer) else {
                        continue;
                    };
                    if let Some(game) = game_from_appinfo(app.appid, &root) {
                        games.push(game);
                    }
                }
                unresolved.extend(batch.iter().copied().filter(|id| !settled.contains(id)));
            }
            pending = unresolved;
            if pending.is_empty() {
                break;
            }
            if attempt == 1 {
                return Err(format!("app info incomplete ({} unresolved)", pending.len()));
            }
        }
        sort_games(&mut games);
        Ok(games)
    }

    pub fn with_games(&self, games: Vec<Game>) -> Library {
        Library {
            owned_apps: self.owned_apps.clone(),
            owned_depots: self.owned_depots.clone(),
            games: Some(games),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn keeps_games_only_and_sorts_by_name() {
        let parse = |t: &str| vdf::parse_text(t.as_bytes()).unwrap();
        let game = parse(
            r#""appinfo" { "common" { "name" "brawlhalla" "type" "Game" "freetogame" "1" } "config" { "installdir" "Brawlhalla" } }"#,
        );
        let tool = parse(r#""appinfo" { "common" { "name" "Proton" "type" "Tool" } }"#);
        let other = parse(r#""appinfo" { "common" { "name" "Alpha" "type" "game" } }"#);
        assert!(game_from_appinfo(5, &tool).is_none());
        let mut games = vec![
            game_from_appinfo(291550, &game).unwrap(),
            game_from_appinfo(10, &other).unwrap(),
        ];
        sort_games(&mut games);
        assert_eq!(games[0].name, "Alpha");
        assert_eq!(games[1].install_dir, "Brawlhalla");
        assert!(games[1].free);
        let json = games_json(&games);
        assert_eq!(json["games"][1]["appId"], 291550);
        assert_eq!(json["games"][1]["type"], "game");
        assert_eq!(json["games"][0]["free"], false);
    }
}
