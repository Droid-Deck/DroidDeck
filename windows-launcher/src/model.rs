use eframe::egui::Color32;
use serde::{Deserialize, Serialize};
use std::collections::BTreeMap;

#[derive(Clone, Debug, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Game {
    #[serde(default)]
    pub key: String,
    #[serde(default = "other_store")]
    pub store: String,
    #[serde(default)]
    pub store_name: String,
    #[serde(default)]
    pub store_id: String,
    #[serde(default)]
    pub app_id: String,
    #[serde(default)]
    pub title: String,
    #[serde(default = "yes")]
    pub owned: bool,
    #[serde(default)]
    pub subscription: bool,
    #[serde(default)]
    pub installed: bool,
    #[serde(default)]
    pub store_url: String,
    #[serde(default)]
    pub cover_url: String,
    #[serde(default)]
    pub hero_url: String,
    #[serde(default)]
    pub launch_target: String,
    #[serde(default)]
    pub local_target: String,
    #[serde(default)]
    pub imported_from: String,
}

#[derive(Clone, Debug, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Catalog {
    #[serde(default)]
    pub schema_version: u32,
    #[serde(default)]
    pub games: Vec<Game>,
}

#[derive(Clone, Copy)]
pub struct Store {
    pub id: &'static str,
    pub label: &'static str,
    pub color: Color32,
}

pub const STORES: &[Store] = &[
    Store {
        id: "steam",
        label: "Steam",
        color: Color32::from_rgb(93, 169, 255),
    },
    Store {
        id: "epic",
        label: "Epic Games",
        color: Color32::from_rgb(236, 238, 242),
    },
    Store {
        id: "ea",
        label: "EA app",
        color: Color32::from_rgb(255, 107, 111),
    },
    Store {
        id: "xbox",
        label: "Xbox",
        color: Color32::from_rgb(111, 212, 126),
    },
    Store {
        id: "gog",
        label: "GOG",
        color: Color32::from_rgb(182, 123, 255),
    },
    Store {
        id: "ubisoft",
        label: "Ubisoft",
        color: Color32::from_rgb(94, 193, 255),
    },
    Store {
        id: "amazon",
        label: "Amazon",
        color: Color32::from_rgb(234, 172, 92),
    },
    Store {
        id: "battlenet",
        label: "Battle.net",
        color: Color32::from_rgb(108, 173, 255),
    },
    Store {
        id: "rockstar",
        label: "Rockstar",
        color: Color32::from_rgb(246, 215, 90),
    },
    Store {
        id: "itch",
        label: "itch.io",
        color: Color32::from_rgb(250, 113, 131),
    },
    Store {
        id: "gamejolt",
        label: "Game Jolt",
        color: Color32::from_rgb(151, 219, 127),
    },
    Store {
        id: "other",
        label: "Otro",
        color: Color32::from_rgb(165, 181, 201),
    },
];

fn yes() -> bool {
    true
}
fn other_store() -> String {
    "other".into()
}

impl Game {
    pub fn normalize(&mut self) {
        self.store = self.store.trim().to_lowercase();
        if self.store.is_empty() {
            self.store = "other".into();
        }
        if self.store_name.is_empty() {
            self.store_name = store_label(&self.store).to_owned();
        }
        if self.store_id.is_empty() {
            self.store_id.clone_from(&self.app_id);
        }
        if self.app_id.is_empty() && self.store == "steam" {
            self.app_id.clone_from(&self.store_id);
        }
        self.title = self.title.trim().to_owned();
        if self.key.is_empty() {
            self.key = stable_key(&self.store, &self.store_id, &self.title);
        }
        self.cover_url = https_only(&self.cover_url);
        self.hero_url = https_only(&self.hero_url);
        self.store_url = https_only(&self.store_url);
    }

    pub fn manual(title: &str, target: &str) -> Self {
        Self {
            title: title.to_owned(),
            store: "other".into(),
            store_name: "PC".into(),
            owned: true,
            installed: true,
            launch_target: target.to_owned(),
            imported_from: "manual".into(),
            ..Default::default()
        }
    }

    pub fn store_color(&self) -> Color32 {
        STORES
            .iter()
            .find(|store| store.id == self.store)
            .map(|store| store.color)
            .unwrap_or(Color32::LIGHT_BLUE)
    }
}

pub fn store_label(id: &str) -> &'static str {
    STORES
        .iter()
        .find(|store| store.id == id)
        .map(|store| store.label)
        .unwrap_or("Otro")
}

pub fn stable_key(store: &str, id: &str, title: &str) -> String {
    if !id.is_empty() {
        format!("{}:{}", store.to_lowercase(), id.to_lowercase())
    } else {
        format!("{}:title:{}", store.to_lowercase(), normalize_title(title))
    }
}

pub fn normalize_title(value: &str) -> String {
    value
        .chars()
        .flat_map(char::to_lowercase)
        .filter(|c| c.is_alphanumeric())
        .collect()
}

pub fn merge_games(rows: Vec<Game>) -> Vec<Game> {
    let mut merged = BTreeMap::<String, Game>::new();
    for mut game in rows {
        if game.title.trim().is_empty() {
            continue;
        }
        game.normalize();
        let key = game.key.clone();
        if let Some(existing) = merged.get_mut(&key) {
            existing.installed |= game.installed;
            existing.owned |= game.owned;
            existing.subscription |= game.subscription;
            if existing.local_target.is_empty() {
                existing.local_target = game.local_target;
            }
            if existing.launch_target.is_empty() {
                existing.launch_target = game.launch_target;
            }
            if existing.cover_url.is_empty() {
                existing.cover_url = game.cover_url;
            }
            if existing.hero_url.is_empty() {
                existing.hero_url = game.hero_url;
            }
            if existing.store_url.is_empty() {
                existing.store_url = game.store_url;
            }
        } else {
            merged.insert(key, game);
        }
    }
    merged.into_values().collect()
}

pub fn available_stores(games: &[Game]) -> Vec<(String, String, usize)> {
    let mut counts = BTreeMap::<String, usize>::new();
    for game in games {
        *counts.entry(game.store.clone()).or_default() += 1;
    }
    counts
        .into_iter()
        .map(|(id, count)| (id.clone(), store_label(&id).to_owned(), count))
        .collect()
}

fn https_only(value: &str) -> String {
    if value.starts_with("https://") {
        value.to_owned()
    } else {
        String::new()
    }
}
