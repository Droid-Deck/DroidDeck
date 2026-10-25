//! Per-game DLSS support derived from NVIDIA's published DLSS games list.
//!
//! The GeForce NOW catalog has no per-game DLSS key. NVIDIA's RTX games page loads its
//! table from a public JSON list, so the core matches catalog titles against that list.
//! The list only decorates catalog responses: when it is missing, oversized, or invalid,
//! every game carries `dlss: null` and the next catalog request retries.

use super::{ServiceError, now_ms};
use crate::proxy::{client_for_settings, config_from_settings};
use crate::store_cache::{CachePolicy, StoreCache};
use icu_normalizer::DecomposingNormalizerBorrowed;
use reqwest::blocking::Client;
use reqwest::header::ACCEPT;
use serde::Serialize;
use serde_json::{Value, json};
use std::collections::HashMap;
use std::io::Read;
use std::sync::{Arc, Condvar, Mutex};
use std::time::Duration;

pub const LIST_URL: &str = "https://www.nvidia.com/content/dam/en-zz/Solutions/geforce/news/nvidia-rtx-games-engines-apps/dlss-rt-games-apps-overrides.json";
const SOURCE: &str = "nvidia-rtx-games-list";
const MAX_LIST_BYTES: usize = 4 * 1024 * 1024;
const MAX_ROWS: usize = 5_000;
const MAX_NAME_BYTES: usize = 512;
const MAX_CELL_BYTES: usize = 64;
const TTL_MS: u64 = 24 * 60 * 60 * 1000;
const RETRY_MS: u64 = 60 * 1000;
const FIRST_LOAD_WAIT: Duration = Duration::from_millis(1500);
const FETCH_TIMEOUT: Duration = Duration::from_secs(15);
const MAX_ANNOTATION_DEPTH: usize = 10;

/// Column order of a cached row; the cache stores only these cells.
const COLUMNS: [&str; 5] = [
    "dlss super resolution",
    "dlss frame generation",
    "dlss multi frame generation",
    "dlss ray reconstruction",
    "dlaa",
];

/// Trailing title words that name an edition of the same game. "Remastered" is
/// deliberately absent: remasters are listed separately and differ in support.
const EDITION_SUFFIXES: [&[&str]; 11] = [
    &["game", "of", "year"],
    &["goty"],
    &["definitive"],
    &["enhanced"],
    &["complete"],
    &["deluxe"],
    &["standard"],
    &["ultimate"],
    &["directors", "cut"],
    &["director", "s", "cut"],
    &["anniversary"],
];

type Row = (String, [String; 5]);

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize)]
#[serde(rename_all = "lowercase")]
pub enum Support {
    Native,
    Override,
}

impl Support {
    fn from_cell(cell: &str) -> Option<Self> {
        if cell.eq_ignore_ascii_case("yes") {
            Some(Self::Native)
        } else if cell.starts_with("NV") {
            Some(Self::Override)
        } else {
            None
        }
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct MultiFrameGeneration {
    max_multiplier: u8,
}

impl MultiFrameGeneration {
    fn from_cell(cell: &str) -> Option<Self> {
        cell.split(|character: char| !character.is_ascii_alphanumeric())
            .find_map(|token| token.strip_suffix(['X', 'x'])?.parse::<u8>().ok())
            .filter(|multiplier| (2..=16).contains(multiplier))
            .map(|max_multiplier| Self { max_multiplier })
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct DlssSupport {
    super_resolution: Option<Support>,
    frame_generation: Option<Support>,
    multi_frame_generation: Option<MultiFrameGeneration>,
    ray_reconstruction: Option<Support>,
    dlaa: Option<Support>,
}

impl DlssSupport {
    /// None when the row lists no DLSS technology (for example ray tracing only).
    fn from_cells([sr, fg, mfg, rr, dlaa]: &[String; 5]) -> Option<Self> {
        let support = Self {
            super_resolution: Support::from_cell(sr),
            frame_generation: Support::from_cell(fg),
            multi_frame_generation: MultiFrameGeneration::from_cell(mfg),
            ray_reconstruction: Support::from_cell(rr),
            dlaa: Support::from_cell(dlaa),
        };
        let listed = support.super_resolution.is_some()
            || support.frame_generation.is_some()
            || support.multi_frame_generation.is_some()
            || support.ray_reconstruction.is_some()
            || support.dlaa.is_some();
        listed.then_some(support)
    }

    fn to_json(self) -> Value {
        let mut value = serde_json::to_value(self).expect("DLSS support serializes");
        value["source"] = json!(SOURCE);
        value
    }
}

fn invalid_list(message: &str) -> ServiceError {
    ServiceError {
        code: "invalid_upstream_response",
        message: format!("DLSS games list rejected: {message}"),
    }
}

/// Parses NVIDIA's list into bounded Game rows. Apps and malformed rows are skipped;
/// an oversized or structurally invalid document is rejected as a whole.
pub fn parse_list(bytes: &[u8]) -> Result<Vec<Row>, ServiceError> {
    if bytes.len() > MAX_LIST_BYTES {
        return Err(invalid_list("larger than 4 MiB"));
    }
    let document: Value =
        serde_json::from_slice(bytes).map_err(|_| invalid_list("not valid JSON"))?;
    let data = document["data"]
        .as_array()
        .ok_or_else(|| invalid_list("no data array"))?;
    if data.len() > MAX_ROWS {
        return Err(invalid_list("more than 5,000 rows"));
    }
    Ok(data
        .iter()
        .filter(|row| row["type"].as_str() == Some("Game"))
        .filter_map(|row| {
            let name = match &row["name"] {
                Value::String(name) => name.trim().to_owned(),
                Value::Number(number) => number.to_string(),
                _ => return None,
            };
            if name.is_empty() || name.len() > MAX_NAME_BYTES {
                return None;
            }
            let cells = COLUMNS.map(|column| {
                row[column]
                    .as_str()
                    .map(str::trim)
                    .filter(|cell| cell.len() <= MAX_CELL_BYTES)
                    .unwrap_or("")
                    .to_owned()
            });
            Some((name, cells))
        })
        .collect())
}

/// Lowercase ASCII words: trademark and zero-width characters removed, NFKD folded,
/// "&" spelled "and", and every other non-alphanumeric run collapsed to one space.
pub fn normalize_title(title: &str) -> String {
    let stripped: String = title
        .chars()
        .filter(|character| {
            !matches!(
                character,
                '\u{2122}'
                    | '\u{00AE}'
                    | '\u{00A9}'
                    | '\u{200B}'
                    | '\u{200C}'
                    | '\u{200D}'
                    | '\u{2060}'
                    | '\u{FEFF}'
            )
        })
        .collect();
    let ascii: String = DecomposingNormalizerBorrowed::new_nfkd()
        .normalize(&stripped)
        .chars()
        .filter(char::is_ascii)
        .collect::<String>()
        .to_ascii_lowercase()
        .replace('&', " and ");
    ascii
        .split(|character: char| !character.is_ascii_alphanumeric())
        .filter(|word| !word.is_empty())
        .collect::<Vec<_>>()
        .join(" ")
}

/// A normalized title without "the"/"tm" and without trailing edition words.
fn loose_title(normalized: &str) -> String {
    let mut words: Vec<&str> = normalized
        .split(' ')
        .filter(|word| !word.is_empty() && *word != "the" && *word != "tm")
        .collect();
    loop {
        let before = words.len();
        if words.len() > 1 && matches!(words.last(), Some(&"edition" | &"pc")) {
            words.pop();
        }
        if let Some(suffix) = EDITION_SUFFIXES
            .iter()
            .find(|suffix| words.len() > suffix.len() && words.ends_with(suffix))
        {
            words.truncate(words.len() - suffix.len());
        }
        if words.len() == before {
            return words.join(" ");
        }
    }
}

#[derive(Default)]
pub struct DlssIndex {
    exact: HashMap<String, Option<DlssSupport>>,
    loose: HashMap<String, Option<DlssSupport>>,
}

impl DlssIndex {
    /// Every Game row is indexed, including rows without DLSS, so an exact title
    /// without DLSS never falls through to a loosely matching edition that has it.
    pub fn from_rows(rows: &[Row]) -> Self {
        let mut index = Self::default();
        for (name, cells) in rows {
            let exact = normalize_title(name);
            if exact.is_empty() {
                continue;
            }
            let support = DlssSupport::from_cells(cells);
            index.loose.entry(loose_title(&exact)).or_insert(support);
            index.exact.entry(exact).or_insert(support);
        }
        index
    }

    pub fn lookup(&self, title: &str) -> Option<DlssSupport> {
        let exact = normalize_title(title);
        if exact.is_empty() {
            return None;
        }
        match self.exact.get(&exact) {
            Some(support) => *support,
            None => self.loose.get(&loose_title(&exact)).copied().flatten(),
        }
    }
}

/// Sets `dlss` on every catalog game in a response. A game is an object with a
/// string `title`, a `variants` array, and a `launchAppId` key, the shape both
/// catalog mappers produce. Games are never nested inside games.
pub fn annotate(value: &mut Value, index: Option<&DlssIndex>) {
    fn visit(value: &mut Value, index: Option<&DlssIndex>, depth: usize) {
        if depth > MAX_ANNOTATION_DEPTH {
            return;
        }
        match value {
            Value::Array(items) => {
                for item in items {
                    visit(item, index, depth + 1);
                }
            }
            Value::Object(object) => {
                if object.get("variants").is_some_and(Value::is_array)
                    && object.contains_key("launchAppId")
                    && let Some(title) = object.get("title").and_then(Value::as_str)
                {
                    match index {
                        Some(index) => {
                            let dlss = index
                                .lookup(title)
                                .map_or(Value::Null, DlssSupport::to_json);
                            object.insert("dlss".to_owned(), dlss);
                        }
                        None => {
                            object.remove("dlss");
                        }
                    }
                    return;
                }
                for child in object.values_mut() {
                    visit(child, index, depth + 1);
                }
            }
            _ => {}
        }
    }
    visit(value, index, 0);
}

fn fetch_list(client: &Client, url: &str) -> Result<Value, ServiceError> {
    let response = client
        .get(url)
        .header(ACCEPT, "application/json")
        .timeout(FETCH_TIMEOUT)
        .send()
        .map_err(|error| ServiceError::network("DLSS games list fetch failed", error))?;
    if !response.status().is_success() {
        return Err(ServiceError::response(
            "DLSS games list fetch failed",
            response,
        ));
    }
    if response
        .content_length()
        .is_some_and(|length| length > MAX_LIST_BYTES as u64)
    {
        return Err(invalid_list("larger than 4 MiB"));
    }
    let mut bytes = Vec::new();
    response
        .take(MAX_LIST_BYTES as u64 + 1)
        .read_to_end(&mut bytes)
        .map_err(|_| invalid_list("the download was interrupted"))?;
    let rows = parse_list(&bytes)?;
    Ok(json!({"metadataKind":"dlssGames","source":SOURCE,"rows":rows}))
}

#[derive(Default)]
struct LoadState {
    index: Option<Arc<DlssIndex>>,
    expires_at: u64,
    retry_at: u64,
    loading: bool,
    first_attempt_done: bool,
    first_wait_claimed: bool,
}

#[derive(Default)]
struct Shared {
    state: Mutex<LoadState>,
    settled: Condvar,
}

/// Owns the in-memory list and its background refresh. Catalog requests never wait on
/// a download except the process's first attempt, and only for `FIRST_LOAD_WAIT`.
pub struct DlssCatalog {
    client: Client,
    url: String,
    cache: Arc<StoreCache>,
    shared: Arc<Shared>,
}

impl DlssCatalog {
    pub fn new(client: Client, url: String, cache: Arc<StoreCache>) -> Self {
        Self {
            client,
            url,
            cache,
            shared: Arc::default(),
        }
    }

    pub fn index(&self, settings: &Value) -> Option<Arc<DlssIndex>> {
        let mut state = self.shared.state.lock().expect("DLSS list poisoned");
        let now = now_ms();
        if !state.loading
            && now >= state.retry_at
            && (state.index.is_none() || now >= state.expires_at)
        {
            state.loading = self.spawn_load(settings);
            if !state.loading {
                state.first_attempt_done = true;
                state.retry_at = now.saturating_add(RETRY_MS);
            }
        }
        if state.index.is_none() && !state.first_attempt_done && !state.first_wait_claimed {
            state.first_wait_claimed = true;
            state = self
                .shared
                .settled
                .wait_timeout_while(state, FIRST_LOAD_WAIT, |state| !state.first_attempt_done)
                .expect("DLSS list poisoned")
                .0;
        }
        state.index.clone()
    }

    fn spawn_load(&self, settings: &Value) -> bool {
        let (Ok(client), Ok(proxy)) = (
            client_for_settings(&self.client, settings),
            config_from_settings(settings),
        ) else {
            return false;
        };
        let scope = json!([
            "dlss-games-v1",
            self.url,
            proxy.map_or_else(|| "direct".to_owned(), |config| config.cache_scope)
        ]);
        let (url, cache, shared) = (self.url.clone(), self.cache.clone(), self.shared.clone());
        std::thread::Builder::new()
            .name("dlss-games-list".into())
            .spawn(move || {
                let loaded = cache
                    .load_with_policy(
                        &scope,
                        &json!(["dlss-games"]),
                        false,
                        CachePolicy {
                            ttl_ms: TTL_MS,
                            allow_stale: true,
                        },
                        || fetch_list(&client, &url),
                    )
                    .and_then(|value| {
                        if let Some(code) = value["error"]["code"].as_str() {
                            eprintln!("dlss games list: refresh failed ({code})");
                        }
                        let rows: Vec<Row> = serde_json::from_value(value["rows"].clone())
                            .map_err(|_| invalid_list("no cached rows"))?;
                        if rows.len() > MAX_ROWS {
                            return Err(invalid_list("more than 5,000 rows"));
                        }
                        Ok((
                            DlssIndex::from_rows(&rows),
                            value["expiresAt"].as_u64().unwrap_or(0),
                        ))
                    });
                let mut state = shared.state.lock().expect("DLSS list poisoned");
                let now = now_ms();
                match loaded {
                    Ok((index, expires_at)) => {
                        state.index = Some(Arc::new(index));
                        state.expires_at = expires_at;
                    }
                    Err(error) => eprintln!("dlss games list: unavailable ({})", error.code),
                }
                if state.expires_at <= now {
                    state.retry_at = now.saturating_add(RETRY_MS);
                }
                state.loading = false;
                state.first_attempt_done = true;
                shared.settled.notify_all();
            })
            .is_ok()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn list(rows: Value) -> Vec<u8> {
        serde_json::to_vec(&json!({"columns":[],"footer":"","data":rows})).unwrap()
    }

    fn row(name: Value, cells: [&str; 5]) -> Value {
        let mut row = json!({"sno":1,"name":name,"type":"Game","ray tracing":"","ai":""});
        for (column, cell) in COLUMNS.iter().zip(cells) {
            row[*column] = json!(cell);
        }
        row
    }

    fn index(rows: Value) -> DlssIndex {
        DlssIndex::from_rows(&parse_list(&list(rows)).unwrap())
    }

    #[test]
    fn parses_string_and_numeric_names_and_skips_apps() {
        let mut app = row(json!("Broadcast"), ["Yes", "", "", "", ""]);
        app["type"] = json!("App");
        let rows = parse_list(&list(json!([
            row(json!("007 First Light"), ["NV, T", "NV, U", "NV, 6X", "NV, T", "NV, T"]),
            row(json!(83), ["NV, T", "", "", "", ""]),
            app,
            {"name":null,"type":"Game"},
            "not a row",
        ])))
        .unwrap();
        assert_eq!(
            rows.iter()
                .map(|(name, _)| name.as_str())
                .collect::<Vec<_>>(),
            ["007 First Light", "83"]
        );
        assert_eq!(rows[0].1[2], "NV, 6X");
    }

    #[test]
    fn maps_cells_to_native_override_and_multiplier() {
        let index = index(json!([
            row(
                json!("Cyberpunk 2077"),
                ["Yes", "Yes", "NV, 6X", "NV, T", "Yes"]
            ),
            row(
                json!("Black Myth: Wukong"),
                ["NV, T", "Yes", "NV, 4X", "", ""]
            ),
            row(json!("Ray Traced Only"), ["", "", "", "", ""]),
        ]));
        assert_eq!(
            index.lookup("Cyberpunk 2077").unwrap().to_json(),
            json!({"superResolution":"native","frameGeneration":"native",
                "multiFrameGeneration":{"maxMultiplier":6},"rayReconstruction":"override",
                "dlaa":"native","source":"nvidia-rtx-games-list"})
        );
        let wukong = index.lookup("Black Myth: Wukong").unwrap();
        assert_eq!(wukong.super_resolution, Some(Support::Override));
        assert_eq!(
            wukong.multi_frame_generation,
            Some(MultiFrameGeneration { max_multiplier: 4 })
        );
        assert_eq!(wukong.ray_reconstruction, None);
        assert_eq!(index.lookup("Ray Traced Only"), None);
        assert_eq!(MultiFrameGeneration::from_cell("Yes"), None);
        assert_eq!(Support::from_cell("Maybe"), None);
    }

    #[test]
    fn rejects_oversized_or_invalid_lists() {
        assert!(parse_list(&vec![b' '; MAX_LIST_BYTES + 1]).is_err());
        assert!(parse_list(b"not json").is_err());
        assert!(parse_list(br#"{"data":{}}"#).is_err());
        assert!(parse_list(br#"[]"#).is_err());
        let rows = vec![json!({"name":"x","type":"App"}); MAX_ROWS + 1];
        assert!(parse_list(&list(Value::Array(rows))).is_err());
        let rows = vec![json!({"name":"x","type":"App"}); MAX_ROWS];
        assert!(parse_list(&list(Value::Array(rows))).unwrap().is_empty());
    }

    #[test]
    fn normalizes_marks_accents_and_punctuation() {
        assert_eq!(normalize_title("Cyberpunk 2077®"), "cyberpunk 2077");
        assert_eq!(normalize_title("Fortnite™"), "fortnite");
        assert_eq!(
            normalize_title("Shadow of the Tomb Raider\u{200B}™ Definitive Edition"),
            "shadow of the tomb raider definitive edition"
        );
        assert_eq!(normalize_title("Pokémon & Friends©"), "pokemon and friends");
        assert_eq!(
            normalize_title("  S.T.A.L.K.E.R. 2:  Heart of Chornobyl "),
            "s t a l k e r 2 heart of chornobyl"
        );
    }

    #[test]
    fn matches_exact_then_loose_editions_but_not_remasters() {
        let index = index(json!([
            row(
                json!("Cyberpunk 2077"),
                ["Yes", "Yes", "NV, 6X", "NV, T", "Yes"]
            ),
            row(json!("Fortnite"), ["NV, T", "", "", "", "NV, T"]),
            row(json!("Control"), ["Yes", "", "", "", ""]),
            row(json!("Alan Wake Remastered"), ["Yes", "", "", "", ""]),
            row(json!("Goat Simulator: Remastered"), ["Yes", "", "", "", ""]),
            row(json!("Battlefield V"), ["Yes", "", "", "", ""]),
            row(json!("The Witcher 3: Wild Hunt"), ["Yes", "", "", "", ""]),
        ]));
        assert!(index.lookup("Cyberpunk 2077®").is_some());
        let fortnite = index.lookup("Fortnite®").unwrap();
        assert_eq!(fortnite.super_resolution, Some(Support::Override));
        assert_eq!(fortnite.frame_generation, None);
        assert!(index.lookup("Control Ultimate Edition").is_some());
        assert!(index.lookup("Battlefield™ V: Definitive").is_some());
        assert!(
            index
                .lookup("The Witcher 3: Wild Hunt - Game of the Year Edition")
                .is_some()
        );
        assert!(
            index
                .lookup("Witcher 3: Wild Hunt Complete Edition")
                .is_some()
        );
        assert_eq!(index.lookup("Alan Wake"), None);
        assert_eq!(index.lookup("Goat Simulator"), None);
        assert_eq!(index.lookup("Control 2"), None);
        assert_eq!(index.lookup("Ultimate"), None);
        assert_eq!(index.lookup("™"), None);
    }

    #[test]
    fn exact_rows_without_dlss_do_not_fall_back_to_editions() {
        let index = index(json!([
            row(json!("Puzzle Quest"), ["", "", "", "", ""]),
            row(json!("Puzzle Quest Deluxe"), ["Yes", "", "", "", ""]),
        ]));
        assert_eq!(index.lookup("Puzzle Quest"), None);
        assert!(index.lookup("Puzzle Quest™ Deluxe").is_some());
    }

    #[test]
    fn catalog_games_gain_dlss_or_null() {
        let index = index(json!([row(
            json!("Cyberpunk 2077"),
            ["Yes", "Yes", "NV, 6X", "NV, T", "Yes"]
        )]));
        let app = |id: &str, title: &str| {
            json!({"id":id,"title":title,"variants":[{"id":format!("{id}1"),"appStore":"Steam",
                "gfn":{"features":[{"key":"RTX_ENABLED","value":"true"}]}}],"gfn":{"playabilityState":"PLAYABLE"}})
        };
        let matched = super::super::catalog::app_to_game(&app("cms-1", "Cyberpunk 2077®")).unwrap();
        let unmatched = super::super::catalog::app_to_game(&app("cms-2", "Alan Wake")).unwrap();
        let mut response = json!({
            "games":[matched.clone(), unmatched],
            "items":[{"sections":[{"games":[matched.clone()]}]},{"kind":"game","game":matched}],
            "catalogRevision":3
        });
        annotate(&mut response, Some(&index));
        assert_eq!(
            response["games"][0]["dlss"]["multiFrameGeneration"],
            json!({"maxMultiplier":6})
        );
        assert_eq!(
            response["games"][0]["dlss"]["source"],
            "nvidia-rtx-games-list"
        );
        assert_eq!(
            response["games"][0]["variants"][0]["features"],
            json!(["rtx"])
        );
        assert!(response["games"][0]["variants"][0].get("dlss").is_none());
        assert!(response["games"][1]["dlss"].is_null());
        assert!(response["games"][1].get("dlss").is_some());
        assert_eq!(
            response["items"][0]["sections"][0]["games"][0]["dlss"]["superResolution"],
            "native"
        );
        assert_eq!(
            response["items"][1]["game"]["dlss"]["rayReconstruction"],
            "override"
        );
        assert!(response.get("dlss").is_none());

        let mut unavailable = json!({"game":response["games"][0].clone()});
        annotate(&mut unavailable, None);
        assert!(unavailable["game"].get("dlss").is_none());
    }

    fn serve(body: &'static [u8], requests: usize) -> String {
        let listener = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
        let url = format!("http://{}/dlss.json", listener.local_addr().unwrap());
        std::thread::spawn(move || {
            use std::io::Write;
            for stream in listener.incoming().take(requests) {
                let mut stream = stream.unwrap();
                let mut request = [0_u8; 4096];
                let _ = stream.read(&mut request);
                let _ = write!(
                    stream,
                    "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: {}\r\nConnection: close\r\n\r\n",
                    body.len()
                );
                let _ = stream.write_all(body);
            }
        });
        url
    }

    #[test]
    fn catalog_loads_once_then_serves_from_the_disk_cache() {
        let body: &'static [u8] = Box::leak(
            list(json!([row(
                json!("Fortnite"),
                ["NV, T", "", "", "", "NV, T"]
            )]))
            .into_boxed_slice(),
        );
        let directory = tempfile::tempdir().unwrap();
        let url = serve(body, 1);
        let cache = Arc::new(StoreCache::new(directory.path().to_path_buf()));
        let catalog = DlssCatalog::new(Client::new(), url.clone(), cache.clone());
        let index = catalog
            .index(&json!({}))
            .expect("first load within the bound");
        assert!(index.lookup("Fortnite®").is_some());

        let restarted = DlssCatalog::new(Client::new(), url, cache);
        assert!(
            restarted
                .index(&json!({}))
                .unwrap()
                .lookup("Fortnite")
                .is_some()
        );
    }

    #[test]
    fn only_one_request_waits_for_a_slow_first_download() {
        let silent = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
        let url = format!("http://{}/dlss.json", silent.local_addr().unwrap());
        let directory = tempfile::tempdir().unwrap();
        let catalog = Arc::new(DlssCatalog::new(
            Client::new(),
            url,
            Arc::new(StoreCache::new(directory.path().to_path_buf())),
        ));
        let started = std::time::Instant::now();
        let concurrent = {
            let catalog = catalog.clone();
            std::thread::spawn(move || {
                std::thread::sleep(Duration::from_millis(200));
                let started = std::time::Instant::now();
                assert!(catalog.index(&json!({})).is_none());
                started.elapsed()
            })
        };
        assert!(catalog.index(&json!({})).is_none());
        assert!(started.elapsed() >= FIRST_LOAD_WAIT);
        assert!(concurrent.join().unwrap() < Duration::from_millis(100));
        let started = std::time::Instant::now();
        assert!(catalog.index(&json!({})).is_none());
        assert!(started.elapsed() < Duration::from_millis(100));
        assert!(catalog.shared.state.lock().unwrap().loading);
        drop(silent);
    }

    #[test]
    fn unavailable_list_yields_no_index_without_failing() {
        let closed = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
        let url = format!("http://{}/dlss.json", closed.local_addr().unwrap());
        drop(closed);
        let directory = tempfile::tempdir().unwrap();
        let catalog = DlssCatalog::new(
            Client::new(),
            url,
            Arc::new(StoreCache::new(directory.path().to_path_buf())),
        );
        let started = std::time::Instant::now();
        assert!(catalog.index(&json!({})).is_none());
        assert!(started.elapsed() <= FIRST_LOAD_WAIT + Duration::from_millis(500));
        let started = std::time::Instant::now();
        assert!(catalog.index(&json!({})).is_none());
        assert!(started.elapsed() < Duration::from_millis(100));
        let state = catalog
            .shared
            .settled
            .wait_timeout_while(
                catalog.shared.state.lock().unwrap(),
                FETCH_TIMEOUT * 2,
                |state| state.loading,
            )
            .unwrap()
            .0;
        assert!(!state.loading && state.index.is_none() && state.retry_at > now_ms());
        drop(state);
        let started = std::time::Instant::now();
        assert!(catalog.index(&json!({})).is_none());
        assert!(started.elapsed() < Duration::from_millis(100));
        assert!(!catalog.shared.state.lock().unwrap().loading);
    }
}
