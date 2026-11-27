use crate::{
    accounts::AccountRecord,
    model::{self, Game},
};
use reqwest::blocking::Client;
use serde_json::{Value, json};
use sha2::{Digest, Sha256};
use std::{
    collections::{HashMap, HashSet},
    sync::mpsc,
    time::{Duration, Instant},
};

const AGENT: &str = "DroidDeckLauncher/0.3";

#[derive(Clone, Debug, Default, serde::Serialize, serde::Deserialize)]
pub struct LoginCapture {
    #[serde(default)]
    pub store: String,
    #[serde(default)]
    pub url: String,
    #[serde(default)]
    pub body: String,
    #[serde(default)]
    pub storage: String,
    #[serde(default)]
    pub data: Value,
    #[serde(default)]
    pub extra: String,
}

#[derive(Clone, Debug, serde::Serialize, serde::Deserialize)]
pub struct ConnectedStore {
    pub account: AccountRecord,
    pub games: Vec<Game>,
}

pub fn connect(capture: LoginCapture, extra: &str) -> Result<ConnectedStore, String> {
    match capture.store.as_str() {
        "steam" => steam_connect(capture.data),
        "epic" => epic_connect(&capture.body),
        "gog" => gog_connect(&capture.url, extra),
        "amazon" => amazon_connect(&capture.url, extra),
        "ea" => ea_connect(&capture.url, extra),
        "ubisoft" => ubisoft_connect(&capture.storage),
        "rockstar" => rockstar_connect(&capture.url, extra, capture.data),
        "gamejolt" => gamejolt_connect(capture.data),
        "itch" => itch_connect(extra),
        store => Err(format!(
            "El inicio de sesión de {store} aún no está disponible en Windows."
        )),
    }
}

pub fn sync(store: &str, saved: &AccountRecord) -> Result<ConnectedStore, String> {
    match store {
        "steam" | "gamejolt" => Err("Esta tienda requiere una sesión web activa. Volvé a abrir su conexión para sincronizar.".into()),
        "epic" => epic_sync(&saved.secret),
        "gog" => gog_sync(&saved.secret),
        "amazon" => amazon_sync(&saved.secret),
        "ea" => ea_sync(&saved.secret),
        "ubisoft" => ubisoft_sync(&saved.secret),
        "rockstar" => rockstar_sync(&saved.secret),
        "itch" => itch_sync(&saved.secret),
        _ => Err(format!("La sincronización de {store} aún no está disponible en Windows.")),
    }
}

fn client() -> Result<Client, String> {
    Client::builder()
        .user_agent(AGENT)
        .connect_timeout(Duration::from_secs(20))
        .timeout(Duration::from_secs(60))
        .build()
        .map_err(|e| format!("No se pudo preparar la conexión segura: {e}"))
}

fn account(store: &str, display_name: String, secret: Value) -> Result<AccountRecord, String> {
    Ok(AccountRecord {
        store: store.to_owned(),
        display_name: display_name.trim().to_owned(),
        secret: serde_json::to_string(&secret).map_err(|e| e.to_string())?,
    })
}

fn game(store: &str, id: &str, title: &str, url: &str, cover: &str, hero: &str) -> Game {
    let mut item = Game {
        key: model::stable_key(store, id, title),
        store: store.to_owned(),
        store_name: model::store_label(store).to_owned(),
        store_id: id.to_owned(),
        app_id: id.to_owned(),
        title: title.to_owned(),
        owned: true,
        installed: false,
        store_url: url.to_owned(),
        cover_url: cover.to_owned(),
        hero_url: hero.to_owned(),
        imported_from: "connected-store".into(),
        ..Default::default()
    };
    item.normalize();
    item
}

fn steam_connect(data: Value) -> Result<ConnectedStore, String> {
    let steam_id = data
        .get("steamId")
        .and_then(Value::as_str)
        .unwrap_or_default();
    if steam_id.is_empty() {
        return Err(
            "Steam no confirmó una sesión iniciada. Iniciá sesión y volvé a sincronizar.".into(),
        );
    }
    let ids = data
        .get("ids")
        .and_then(Value::as_array)
        .ok_or("Steam no devolvió los juegos de la cuenta")?;
    let games = steam_games(
        ids.iter()
            .filter_map(Value::as_u64)
            .filter(|id| *id > 0)
            .map(|id| id.to_string())
            .collect(),
    );
    Ok(ConnectedStore {
        account: account(
            "steam",
            data.get("displayName")
                .and_then(Value::as_str)
                .unwrap_or(steam_id)
                .to_owned(),
            json!({"steamId":steam_id}),
        )?,
        games,
    })
}

enum SteamDetail {
    Game(Game),
    Excluded,
    Failed,
}

fn steam_games(ids: Vec<String>) -> Vec<Game> {
    let mut ids: Vec<String> = ids
        .into_iter()
        .collect::<HashSet<_>>()
        .into_iter()
        .collect();
    ids.sort();
    let mut games: HashMap<String, Game> = ids
        .iter()
        .map(|id| {
            (
                id.clone(),
                game(
                    "steam",
                    id,
                    &format!("Steam game {id}"),
                    &format!("https://store.steampowered.com/app/{id}/"),
                    &format!(
                        "https://cdn.cloudflare.steamstatic.com/steam/apps/{id}/library_600x900.jpg"
                    ),
                    &format!(
                        "https://cdn.cloudflare.steamstatic.com/steam/apps/{id}/library_hero.jpg"
                    ),
                ),
            )
        })
        .collect();
    let pool = threadpool::ThreadPool::new(8);
    let (tx, rx) = mpsc::channel();
    let job_tx = tx.clone();
    let mut pending = ids.into_iter();
    let deadline = Instant::now() + Duration::from_secs(20);
    let mut in_flight = 0;
    let schedule = |id: String, tx: mpsc::Sender<(String, SteamDetail)>| {
        pool.execute(move || {
            let result = steam_detail(&id);
            let _ = tx.send((id, result));
        });
    };
    for _ in 0..8 {
        if let Some(id) = pending.next() {
            in_flight += 1;
            schedule(id, job_tx.clone());
        }
    }
    drop(tx);
    while in_flight > 0 {
        let remaining = deadline.saturating_duration_since(Instant::now());
        if remaining.is_zero() {
            break;
        }
        let Ok((id, result)) = rx.recv_timeout(remaining) else {
            break;
        };
        in_flight -= 1;
        match result {
            SteamDetail::Game(item) => {
                games.insert(id, item);
            }
            SteamDetail::Excluded => {
                games.remove(&id);
            }
            SteamDetail::Failed => {}
        }
        if Instant::now() < deadline {
            if let Some(id) = pending.next() {
                in_flight += 1;
                schedule(id, job_tx.clone());
            }
        }
    }
    pool.join();
    let mut games: Vec<Game> = games.into_values().collect();
    games.sort_by(|a, b| a.title.to_lowercase().cmp(&b.title.to_lowercase()));
    games
}

fn steam_detail(id: &str) -> SteamDetail {
    let Ok(http) = Client::builder()
        .user_agent(AGENT)
        .connect_timeout(Duration::from_secs(5))
        .timeout(Duration::from_secs(10))
        .build()
    else {
        return SteamDetail::Failed;
    };
    let Ok(response) = http
        .get("https://store.steampowered.com/api/appdetails")
        .query(&[("appids", id), ("cc", "us"), ("l", "english")])
        .send()
    else {
        return SteamDetail::Failed;
    };
    if !response.status().is_success() {
        return SteamDetail::Failed;
    }
    let Ok(data) = response.json::<Value>() else {
        return SteamDetail::Failed;
    };
    let Some(details) = data.get(id).and_then(|entry| entry.get("data")) else {
        return SteamDetail::Failed;
    };
    let Some(title) = details.get("name").and_then(Value::as_str).map(str::trim) else {
        return SteamDetail::Failed;
    };
    if title.is_empty() {
        return SteamDetail::Failed;
    }
    if details
        .get("type")
        .and_then(Value::as_str)
        .is_some_and(|kind| kind != "game")
    {
        return SteamDetail::Excluded;
    }
    let cover = details
        .get("header_image")
        .and_then(Value::as_str)
        .unwrap_or("");
    SteamDetail::Game(
        game(
            "steam",
            id,
            title,
            &format!("https://store.steampowered.com/app/{id}/"),
            &format!("https://cdn.cloudflare.steamstatic.com/steam/apps/{id}/library_600x900.jpg"),
            &format!("https://cdn.cloudflare.steamstatic.com/steam/apps/{id}/library_hero.jpg"),
        )
        .with_fallback_cover(cover),
    )
}

trait FallbackCover {
    fn with_fallback_cover(self, cover: &str) -> Self;
}
impl FallbackCover for Game {
    fn with_fallback_cover(mut self, cover: &str) -> Self {
        if !cover.is_empty() {
            self.hero_url = cover.to_owned();
        }
        self
    }
}

fn epic_connect(body: &str) -> Result<ConnectedStore, String> {
    let code = epic_code(body).ok_or(
        "Epic no devolvió el código de autorización. Volvé a completar el inicio de sesión.",
    )?;
    let token = epic_exchange(&code)?;
    epic_from_token(token)
}

fn epic_code(text: &str) -> Option<String> {
    let normalized = text
        .replace("\\n", "")
        .replace("\\r", "")
        .replace("\\\"", "\"");
    if let Ok(value) = serde_json::from_str::<Value>(normalized.trim().trim_matches('"')) {
        if let Some(code) = value
            .get("authorizationCode")
            .and_then(Value::as_str)
            .filter(|s| !s.is_empty())
        {
            return Some(code.to_owned());
        }
    }
    regex::Regex::new(r#""authorizationCode"\s*:\s*"([^"]+)""#)
        .ok()?
        .captures(&normalized)?
        .get(1)
        .map(|m| m.as_str().to_owned())
}

fn epic_exchange(code: &str) -> Result<Value, String> {
    let response = client()?
        .post("https://account-public-service-prod03.ol.epicgames.com/account/api/oauth/token")
        .basic_auth(
            "34a02cf8f4414e29b15921876da36f9a",
            Some("daafbccc737745039dffe53d94fc76cf"),
        )
        .form(&[
            ("grant_type", "authorization_code"),
            ("code", code),
            ("token_type", "eg1"),
        ])
        .send()
        .map_err(|e| format!("Epic no pudo validar la sesión: {e}"))?;
    if !response.status().is_success() {
        return Err(format!(
            "Epic rechazó la sesión (HTTP {})",
            response.status()
        ));
    }
    response
        .json()
        .map_err(|_| "Epic respondió con un token ilegible".into())
}

fn epic_from_token(mut token: Value) -> Result<ConnectedStore, String> {
    let access = token
        .get("access_token")
        .and_then(Value::as_str)
        .ok_or("Epic no devolvió un token")?
        .to_owned();
    let games = epic_list(&access)?;
    let name = token
        .get("displayName")
        .and_then(Value::as_str)
        .or_else(|| token.get("account_id").and_then(Value::as_str))
        .unwrap_or("Epic Games")
        .to_owned();
    token["expiresAt"] = json!(
        now_ms()
            + token
                .get("expires_in")
                .and_then(Value::as_i64)
                .unwrap_or(7200)
                * 1000
    );
    Ok(ConnectedStore {
        account: account("epic", name, token)?,
        games,
    })
}

fn epic_list(token: &str) -> Result<Vec<Game>, String> {
    let http = client()?;
    let mut cursor = String::new();
    let mut games = Vec::new();
    for _ in 0..100 {
        let mut request = http.get("https://library-service.live.use1a.on.epicgames.com/library/api/public/items?includeMetadata=true").bearer_auth(token);
        if !cursor.is_empty() {
            request = request.query(&[("cursor", cursor.as_str())]);
        }
        let response = request
            .send()
            .map_err(|e| format!("No se pudo leer la biblioteca de Epic: {e}"))?;
        if response.status() == reqwest::StatusCode::UNAUTHORIZED {
            return Err("La sesión de Epic venció. Volvé a conectarla.".into());
        }
        let body: Value = response
            .error_for_status()
            .map_err(|e| format!("Epic rechazó la biblioteca: {e}"))?
            .json()
            .map_err(|_| "Epic devolvió una biblioteca ilegible".to_owned())?;
        if let Some(records) = body.get("records").and_then(Value::as_array) {
            for record in records {
                let title = record
                    .get("appName")
                    .and_then(Value::as_str)
                    .unwrap_or("")
                    .trim();
                let id = record
                    .get("catalogItemId")
                    .and_then(Value::as_str)
                    .filter(|v| !v.is_empty())
                    .or_else(|| record.get("namespace").and_then(Value::as_str))
                    .unwrap_or("");
                let name = record
                    .get("title")
                    .and_then(Value::as_str)
                    .filter(|v| !v.is_empty())
                    .unwrap_or(title);
                let platforms = record.get("platform").and_then(Value::as_array);
                if name.is_empty()
                    || record.get("sandboxType").and_then(Value::as_str) == Some("PRIVATE")
                    || platforms.is_some_and(|v| {
                        !v.is_empty()
                            && !v
                                .iter()
                                .any(|p| matches!(p.as_str(), Some("Windows" | "Win32")))
                    })
                {
                    continue;
                }
                let store_url = format!(
                    "https://store.epicgames.com/en-US/p/{}",
                    title.to_lowercase().replace('_', "-")
                );
                games.push(game("epic", id, name, &store_url, "", ""));
            }
        }
        cursor = body
            .pointer("/responseMetadata/nextCursor")
            .and_then(Value::as_str)
            .unwrap_or("")
            .to_owned();
        if cursor.is_empty() {
            break;
        }
    }
    Ok(model::merge_games(games))
}

fn epic_sync(secret: &str) -> Result<ConnectedStore, String> {
    let mut token: Value = serde_json::from_str(secret)
        .map_err(|_| "La sesión de Epic no se puede leer".to_owned())?;
    let expires = token
        .get("expiresAt")
        .and_then(Value::as_i64)
        .unwrap_or_default();
    if expires < now_ms() + 60_000 {
        let refresh = token
            .get("refresh_token")
            .and_then(Value::as_str)
            .filter(|s| !s.is_empty())
            .ok_or("Epic necesita que vuelvas a iniciar sesión")?;
        let response = client()?
            .post("https://account-public-service-prod03.ol.epicgames.com/account/api/oauth/token")
            .basic_auth(
                "34a02cf8f4414e29b15921876da36f9a",
                Some("daafbccc737745039dffe53d94fc76cf"),
            )
            .form(&[
                ("grant_type", "refresh_token"),
                ("refresh_token", refresh),
                ("token_type", "eg1"),
            ])
            .send()
            .map_err(|e| format!("No se pudo renovar Epic: {e}"))?;
        if !response.status().is_success() {
            return Err("Epic necesita que vuelvas a iniciar sesión".into());
        }
        token = response
            .json()
            .map_err(|_| "Epic devolvió un token ilegible".to_owned())?;
        token["expiresAt"] = json!(
            now_ms()
                + token
                    .get("expires_in")
                    .and_then(Value::as_i64)
                    .unwrap_or(7200)
                    * 1000
        );
    }
    let access = token
        .get("access_token")
        .and_then(Value::as_str)
        .ok_or("Falta la sesión de Epic")?;
    let games = epic_list(access)?;
    let display = token
        .get("displayName")
        .and_then(Value::as_str)
        .unwrap_or("Epic Games")
        .to_owned();
    Ok(ConnectedStore {
        account: account("epic", display, token)?,
        games,
    })
}

fn gog_connect(url: &str, extra: &str) -> Result<ConnectedStore, String> {
    let url = url::Url::parse(url).map_err(|_| "GOG devolvió una dirección inválida".to_owned())?;
    if !url
        .as_str()
        .starts_with("https://embed.gog.com/on_login_success")
    {
        return Err("GOG todavía no completó el inicio de sesión.".into());
    }
    let fragment = url
        .fragment()
        .ok_or("GOG no devolvió los datos de sesión")?;
    let values: HashSet<(String, String)> = url::form_urlencoded::parse(fragment.as_bytes())
        .map(|(k, v)| (k.into_owned(), v.into_owned()))
        .collect();
    let get = |key: &str| {
        values
            .iter()
            .find(|(k, _)| k == key)
            .map(|(_, v)| v.clone())
            .unwrap_or_default()
    };
    let state = serde_json::from_str::<Value>(extra)
        .ok()
        .and_then(|v| v.get("state").and_then(Value::as_str).map(str::to_owned))
        .unwrap_or_default();
    if !state.is_empty() && get("state") != state {
        return Err("GOG rechazó la verificación de seguridad de la cuenta.".into());
    }
    let access = get("access_token");
    let refresh = get("refresh_token");
    if access.is_empty() {
        return Err("GOG no devolvió una sesión válida.".into());
    }
    let secret = json!({"access_token":access,"refresh_token":refresh,"user_id":get("user_id"),"expires_at":now_ms()+3600_000});
    gog_from_secret(secret)
}

fn gog_from_secret(mut secret: Value) -> Result<ConnectedStore, String> {
    let access = secret
        .get("access_token")
        .and_then(Value::as_str)
        .ok_or("Falta la sesión de GOG")?;
    let profile = client()?
        .get("https://embed.gog.com/userData.json")
        .bearer_auth(access)
        .header(
            "User-Agent",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) GOG Galaxy/2.0",
        )
        .send()
        .map_err(|e| format!("No se pudo validar GOG: {e}"))?;
    if !profile.status().is_success() {
        return Err("GOG rechazó la sesión. Volvé a conectarla.".into());
    }
    let profile: Value = profile
        .json()
        .map_err(|_| "GOG devolvió un perfil ilegible".to_owned())?;
    let display = profile
        .get("username")
        .and_then(Value::as_str)
        .filter(|s| !s.is_empty())
        .unwrap_or("GOG")
        .to_owned();
    let user_id = profile
        .get("galaxyUserId")
        .or_else(|| profile.get("userId"))
        .and_then(Value::as_str)
        .unwrap_or("")
        .to_owned();
    secret["user_id"] = json!(user_id);
    secret["display_name"] = json!(display);
    if secret.get("expires_at").and_then(Value::as_i64).is_none() {
        secret["expires_at"] = json!(now_ms() + 3600_000);
    }
    let games = gog_list(
        secret
            .get("access_token")
            .and_then(Value::as_str)
            .unwrap_or_default(),
    )?;
    Ok(ConnectedStore {
        account: account("gog", display, secret)?,
        games,
    })
}

fn gog_list(token: &str) -> Result<Vec<Game>, String> {
    let mut games = Vec::new();
    let http = client()?;
    for page in 1..=100 {
        let response = http
            .get("https://embed.gog.com/account/getFilteredProducts")
            .query(&[
                ("mediaType", "1"),
                ("hiddenFlag", "0"),
                ("sortBy", "title"),
                ("page", &page.to_string()),
            ])
            .bearer_auth(token)
            .header(
                "User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) GOG Galaxy/2.0",
            )
            .send()
            .map_err(|e| format!("No se pudo leer la biblioteca GOG: {e}"))?;
        if response.status() == reqwest::StatusCode::UNAUTHORIZED {
            return Err("La sesión de GOG venció. Volvé a conectarla.".into());
        }
        let data: Value = response
            .error_for_status()
            .map_err(|e| format!("GOG rechazó la biblioteca: {e}"))?
            .json()
            .map_err(|_| "GOG devolvió una biblioteca ilegible".to_owned())?;
        let Some(products) = data.get("products").and_then(Value::as_array) else {
            break;
        };
        if products.is_empty() {
            break;
        }
        for product in products {
            let id = product
                .get("id")
                .and_then(Value::as_i64)
                .unwrap_or_default()
                .to_string();
            let title = product
                .get("title")
                .and_then(Value::as_str)
                .unwrap_or("")
                .trim();
            if id == "0"
                || title.is_empty()
                || product.get("isMovie").and_then(Value::as_bool) == Some(true)
            {
                continue;
            }
            let image = product.get("image").and_then(Value::as_str).unwrap_or("");
            games.push(game(
                "gog",
                &id,
                title,
                &format!("https://www.gog.com/en/game/{id}"),
                image,
                image,
            ));
        }
        let total_pages = data
            .get("totalPages")
            .and_then(Value::as_u64)
            .unwrap_or(page as u64);
        if page as u64 >= total_pages {
            break;
        }
    }
    Ok(model::merge_games(games))
}

fn gog_sync(secret: &str) -> Result<ConnectedStore, String> {
    let mut saved: Value =
        serde_json::from_str(secret).map_err(|_| "La sesión de GOG no se puede leer".to_owned())?;
    if saved
        .get("expires_at")
        .and_then(Value::as_i64)
        .unwrap_or_default()
        < now_ms() + 60_000
    {
        let refresh = saved
            .get("refresh_token")
            .and_then(Value::as_str)
            .unwrap_or_default();
        if refresh.is_empty() {
            return Err("GOG necesita que vuelvas a iniciar sesión".into());
        }
        let response = client()?
            .post("https://auth.gog.com/token")
            .query(&[
                ("client_id", "46899977096215655"),
                (
                    "client_secret",
                    "9d85c43b1482497dbbce61f6e4aa173a433796eeae2ca8c5f6129f2dc4de46d9",
                ),
                ("grant_type", "refresh_token"),
                ("refresh_token", refresh),
            ])
            .header("User-Agent", "GOG Galaxy")
            .send()
            .map_err(|e| format!("No se pudo renovar GOG: {}", e.without_url()))?;
        if !response.status().is_success() {
            return Err("GOG necesita que vuelvas a iniciar sesión".into());
        }
        let fresh: Value = response
            .json()
            .map_err(|_| "GOG devolvió un token ilegible".to_owned())?;
        for key in ["access_token", "refresh_token", "expires_in", "user_id"] {
            if let Some(v) = fresh.get(key) {
                saved[key] = v.clone();
            }
        }
        saved["expires_at"] = json!(
            now_ms()
                + fresh
                    .get("expires_in")
                    .and_then(Value::as_i64)
                    .unwrap_or(3600)
                    * 1000
        );
    }
    gog_from_secret(saved)
}

fn amazon_connect(url: &str, extra: &str) -> Result<ConnectedStore, String> {
    let code = amazon::auth::code_from_url(url)
        .ok_or("Amazon todavía no completó el inicio de sesión.")?;
    let saved: Value = serde_json::from_str(extra)
        .map_err(|_| "Se perdió la solicitud de inicio de sesión de Amazon".to_owned())?;
    let attempt = amazon::auth::Attempt {
        serial: saved
            .get("serial")
            .and_then(Value::as_str)
            .unwrap_or_default()
            .to_owned(),
        client_id: saved
            .get("clientId")
            .and_then(Value::as_str)
            .unwrap_or_default()
            .to_owned(),
        verifier: saved
            .get("verifier")
            .and_then(Value::as_str)
            .unwrap_or_default()
            .to_owned(),
    };
    let tokens = amazon::auth::register(&code, &attempt)?;
    let secret = json!({"access_token":tokens.access_token,"refresh_token":tokens.refresh_token,"expires_at":tokens.expires_at_ms,"device_serial":tokens.device_serial,"client_id":tokens.client_id,"display_name":tokens.display_name});
    amazon_from_secret(secret)
}

fn amazon_from_secret(secret: Value) -> Result<ConnectedStore, String> {
    let access = secret
        .get("access_token")
        .and_then(Value::as_str)
        .ok_or("Falta la sesión de Amazon")?;
    let serial = secret
        .get("device_serial")
        .and_then(Value::as_str)
        .unwrap_or_default();
    let games = amazon::library::list(access, serial)
        .map_err(|e| e.message())?
        .into_iter()
        .filter(|g| !g.is_dlc())
        .map(|g| {
            game(
                "amazon",
                &g.product_id,
                &g.title,
                &format!("https://gaming.amazon.com/home"),
                &g.art,
                &g.hero,
            )
        })
        .collect();
    let display = secret
        .get("display_name")
        .and_then(Value::as_str)
        .unwrap_or("Amazon Games")
        .to_owned();
    Ok(ConnectedStore {
        account: account("amazon", display, secret)?,
        games,
    })
}

fn amazon_sync(secret: &str) -> Result<ConnectedStore, String> {
    let mut saved: Value = serde_json::from_str(secret)
        .map_err(|_| "La sesión de Amazon no se puede leer".to_owned())?;
    if saved
        .get("expires_at")
        .and_then(Value::as_i64)
        .unwrap_or_default()
        < now_ms() + 60_000
    {
        let refresh = saved
            .get("refresh_token")
            .and_then(Value::as_str)
            .ok_or("Amazon necesita que vuelvas a iniciar sesión")?;
        let (access, expires) = amazon::auth::refresh(refresh)?;
        saved["access_token"] = json!(access);
        saved["expires_at"] = json!(expires);
    }
    amazon_from_secret(saved)
}

pub fn ea_auth_start() -> Result<(String, String), String> {
    use base64::Engine;
    let mut random = [0u8; 48];
    getrandom::fill(&mut random)
        .map_err(|e| format!("No se pudo iniciar EA de forma segura: {e}"))?;
    let device = random[..16]
        .iter()
        .map(|b| format!("{b:02x}"))
        .collect::<String>()
        .to_uppercase();
    let verifier = base64::engine::general_purpose::URL_SAFE_NO_PAD.encode(&random[16..]);
    let challenge = base64::engine::general_purpose::URL_SAFE_NO_PAD
        .encode(Sha256::digest(verifier.as_bytes()));
    let pc_sign = ea_pc_sign(&device)?;
    let mut params = url::form_urlencoded::Serializer::new(String::new());
    params.append_pair("client_id", "JUNO_PC_CLIENT");
    params.append_pair("sbiod_enabled", "false");
    params.append_pair("response_type", "code");
    params.append_pair("locale", "en_US");
    params.append_pair("pc_sign", &pc_sign);
    params.append_pair(
        "nonce",
        &format!(
            "{}",
            u32::from_le_bytes(random[44..48].try_into().unwrap_or([0; 4]))
        ),
    );
    params.append_pair("code_challenge_method", "S256");
    params.append_pair("code_challenge", &challenge);
    Ok((
        format!("https://accounts.ea.com/connect/auth?{}", params.finish()),
        verifier,
    ))
}

fn ea_pc_sign(device: &str) -> Result<String, String> {
    use base64::Engine;
    use hmac::{Hmac, Mac};
    use time::format_description::FormatItem;
    const FORMAT: &[FormatItem<'_>] = time::macros::format_description!(
        "[year]-[month]-[day] [hour]:[minute]:[second]:[subsecond digits:3]"
    );
    let stamp = time::OffsetDateTime::now_utc()
        .format(FORMAT)
        .map_err(|e| e.to_string())?;
    let mut mid = 0xcbf29ce484222325u64;
    for b in format!("DroidDeck{}DroidDeck", device.to_uppercase()).bytes() {
        mid = (mid ^ b as u64).wrapping_mul(0x100000001b3);
    }
    let version = "v1";
    let payload = json!({"av":"v1","bsn":"None","gid":0,"hsn":"None","mid":mid.to_string(),"msn":device.to_uppercase(),"sv":version,"ts":stamp}).to_string();
    let encoded = base64::engine::general_purpose::URL_SAFE_NO_PAD.encode(payload.as_bytes());
    let key = b"ISa3dpGOc8wW7Adn4auACSQmaccrOyR2";
    let mut mac = Hmac::<Sha256>::new_from_slice(key).map_err(|e| e.to_string())?;
    mac.update(encoded.as_bytes());
    Ok(format!(
        "{}.{}",
        encoded,
        base64::engine::general_purpose::URL_SAFE_NO_PAD.encode(mac.finalize().into_bytes())
    ))
}

fn ea_connect(url: &str, extra: &str) -> Result<ConnectedStore, String> {
    let (code, state) = callback_values(url);
    let saved: Value = serde_json::from_str(extra)
        .map_err(|_| "Se perdió la solicitud de inicio de sesión de EA".to_owned())?;
    let expected = saved
        .get("state")
        .and_then(Value::as_str)
        .unwrap_or_default();
    if !expected.is_empty() && state != expected {
        return Err("EA rechazó la verificación de seguridad de la cuenta.".into());
    }
    if code.is_empty() {
        return Err("EA todavía no completó el inicio de sesión.".into());
    }
    let verifier = saved
        .get("verifier")
        .and_then(Value::as_str)
        .ok_or("Falta la verificación PKCE de EA")?;
    let token = ea_token(&[
        ("grant_type", "authorization_code"),
        ("code", code.as_str()),
        ("code_verifier", verifier),
        ("redirect_uri", "qrc:///html/login_successful.html"),
        ("token_format", "JWS"),
    ])?;
    ea_from_secret(token)
}

fn ea_token(params: &[(&str, &str)]) -> Result<Value, String> {
    let response = client()?.post("https://accounts.ea.com/connect/token")
        .form(&params.iter().copied().chain([
            ("client_id", "JUNO_PC_CLIENT"),
            ("client_secret", "4mRLtYMb6vq9qglomWEaT4ChxsXWcyqbQpuBNfMPOYOiDmYYQmjuaBsF2Zp0RyVeWkfqhE9TuGgAw7te"),
        ]).collect::<Vec<_>>())
        .send().map_err(|e| format!("No se pudo validar la sesión de EA: {e}"))?;
    if !response.status().is_success() {
        return Err(format!("EA rechazó la sesión (HTTP {})", response.status()));
    }
    response
        .json()
        .map_err(|_| "EA devolvió un token ilegible".into())
}

fn ea_from_secret(mut secret: Value) -> Result<ConnectedStore, String> {
    let access = secret
        .get("access_token")
        .and_then(Value::as_str)
        .ok_or("Falta la sesión de EA")?
        .to_owned();
    let profile = ea_profile(&access).unwrap_or_default();
    let display = profile
        .get("displayName")
        .and_then(Value::as_str)
        .or_else(|| profile.get("eaId").and_then(Value::as_str))
        .unwrap_or("EA")
        .to_owned();
    secret["display_name"] = json!(display);
    if secret.get("expires_at").and_then(Value::as_i64).is_none() {
        secret["expires_at"] = json!(
            now_ms()
                + secret
                    .get("expires_in")
                    .and_then(Value::as_i64)
                    .unwrap_or(3600)
                    * 1000
        );
    }
    let games = ea_list(&access)?;
    Ok(ConnectedStore {
        account: account("ea", display, secret)?,
        games,
    })
}

fn ea_profile(token: &str) -> Option<Value> {
    let query = "query GetUserPlayer { me { pd player { displayName uniqueName } } }";
    let response: Value = client()
        .ok()?
        .post("https://service-aggregation-layer.juno.ea.com/graphql")
        .bearer_auth(token)
        .json(&json!({"query":query}))
        .send()
        .ok()?
        .json()
        .ok()?;
    response.pointer("/data/me/player").cloned()
}

fn ea_list(token: &str) -> Result<Vec<Game>, String> {
    let query = include_str!(concat!(
        env!("CARGO_MANIFEST_DIR"),
        "/../app/src/main/resources/ea/graphql/getPreloadedOwnedGames.gql"
    ));
    let http = client()?;
    let mut next = "0".to_owned();
    let mut out = Vec::new();
    for _ in 0..50 {
        let variables = json!({"isMac":false,"limit":100,"next":next,"type":["DIGITAL_FULL_GAME","PACKAGED_FULL_GAME"],"storefronts":["EA"],"platforms":["PC"]});
        let response = http.post("https://service-aggregation-layer.juno.ea.com/graphql").bearer_auth(token)
            .json(&json!({"operationName":"getPreloadedOwnedGames","variables":variables,"query":query}))
            .send().map_err(|e| format!("No se pudo leer la biblioteca de EA: {e}"))?;
        if response.status() == reqwest::StatusCode::UNAUTHORIZED {
            return Err("La sesión de EA venció. Volvé a conectarla.".into());
        }
        let body: Value = response
            .error_for_status()
            .map_err(|e| format!("EA rechazó la biblioteca: {e}"))?
            .json()
            .map_err(|_| "EA devolvió una biblioteca ilegible".to_owned())?;
        let items = body
            .pointer("/data/me/ownedGameProducts/items")
            .and_then(Value::as_array)
            .ok_or("EA devolvió una respuesta de biblioteca inválida")?;
        {
            for item in items {
                let product = item.get("product").unwrap_or(&Value::Null);
                let base = product.get("baseItem").unwrap_or(&Value::Null);
                let id = item
                    .get("originOfferId")
                    .and_then(Value::as_str)
                    .unwrap_or("");
                let title = base
                    .get("title")
                    .and_then(Value::as_str)
                    .filter(|s| !s.is_empty())
                    .or_else(|| product.get("name").and_then(Value::as_str))
                    .unwrap_or("")
                    .trim();
                if id.is_empty()
                    || title.is_empty()
                    || product
                        .get("trialDetails")
                        .is_some_and(|value| !value.is_null())
                {
                    continue;
                }
                let slug = product
                    .get("gameSlug")
                    .and_then(Value::as_str)
                    .unwrap_or("");
                let url = if slug.is_empty() {
                    "https://www.ea.com/games".to_owned()
                } else {
                    format!("https://www.ea.com/games/{slug}")
                };
                out.push(game("ea", id, title, &url, "", ""));
            }
        }
        let owned = body
            .pointer("/data/me/ownedGameProducts")
            .unwrap_or(&Value::Null);
        let following = owned.get("next").and_then(Value::as_str).unwrap_or("");
        if following.is_empty() || following == next {
            break;
        }
        next = following.to_owned();
    }
    Ok(model::merge_games(out))
}

fn ea_sync(secret: &str) -> Result<ConnectedStore, String> {
    let mut saved: Value =
        serde_json::from_str(secret).map_err(|_| "La sesión de EA no se puede leer".to_owned())?;
    if saved
        .get("expires_at")
        .and_then(Value::as_i64)
        .unwrap_or_default()
        < now_ms() + 60_000
    {
        let refresh = saved
            .get("refresh_token")
            .and_then(Value::as_str)
            .unwrap_or_default();
        if refresh.is_empty() {
            return Err("EA necesita que vuelvas a iniciar sesión".into());
        }
        let token = ea_token(&[("grant_type", "refresh_token"), ("refresh_token", refresh)])?;
        saved = token;
    }
    ea_from_secret(saved)
}

fn callback_values(raw: &str) -> (String, String) {
    let mut code = String::new();
    let mut state = String::new();
    if let Ok(url) = url::Url::parse(raw) {
        for (k, v) in url.query_pairs().chain(
            url.fragment()
                .into_iter()
                .flat_map(|f| url::form_urlencoded::parse(f.as_bytes())),
        ) {
            if k == "code" {
                code = v.to_string();
            }
            if k == "state" {
                state = v.to_string();
            }
        }
    }
    (code, state)
}

fn ubisoft_connect(dump: &str) -> Result<ConnectedStore, String> {
    let value: Value = serde_json::from_str(dump)
        .map_err(|_| "Ubisoft no devolvió los datos de inicio de sesión".to_owned())?;
    let ticket = find_value(&value, "ticket")
        .ok_or("No se encontró el ticket de inicio de sesión de Ubisoft")?;
    let remember = find_value(&value, "rememberMeTicket").unwrap_or_default();
    let secret = ubi_session(&format!("ubi_v1 t={ticket}"), &remember)?;
    ubisoft_from_secret(secret)
}

fn find_value(value: &Value, key: &str) -> Option<String> {
    match value {
        Value::Object(map) => {
            if let Some(v) = map
                .get(key)
                .and_then(Value::as_str)
                .filter(|s| !s.is_empty())
            {
                return Some(v.to_owned());
            }
            map.values().find_map(|v| find_value(v, key))
        }
        Value::Array(items) => items.iter().find_map(|v| find_value(v, key)),
        Value::String(text) if text.starts_with('{') || text.starts_with('[') => {
            serde_json::from_str::<Value>(text)
                .ok()
                .and_then(|v| find_value(&v, key))
        }
        _ => None,
    }
}

fn ubi_session(auth: &str, remember: &str) -> Result<Value, String> {
    let http = client()?;
    let request = || {
        http.post("https://public-ubiservices.ubi.com/v3/profiles/sessions")
            .header("Ubi-AppId", "f68a4bb5-608a-4ff2-8123-be8ef797e0a6")
            .header("Ubi-RequestedPlatformType", "uplay")
            .header("Authorization", auth)
            .json(&json!({"rememberMe":true}))
    };
    let first = request()
        .send()
        .map_err(|e| format!("No se pudo validar Ubisoft: {e}"))?;
    let challenge = first
        .headers()
        .get("ubi-challenge")
        .and_then(|h| h.to_str().ok())
        .map(str::to_owned);
    let response = if let Some(challenge) = challenge {
        request()
            .header("Ubi-Challenge", challenge)
            .send()
            .map_err(|e| format!("No se pudo completar el desafío de Ubisoft: {e}"))?
    } else {
        first
    };
    if !response.status().is_success() {
        return Err(format!(
            "Ubisoft rechazó la sesión (HTTP {})",
            response.status()
        ));
    }
    let body: Value = response
        .json()
        .map_err(|_| "Ubisoft devolvió una sesión ilegible".to_owned())?;
    let ticket = body
        .get("ticket")
        .and_then(Value::as_str)
        .unwrap_or_default();
    if ticket.is_empty() {
        return Err("Ubisoft no entregó una sesión utilizable".into());
    }
    Ok(
        json!({"ticket":ticket,"sessionId":body.get("sessionId").and_then(Value::as_str).unwrap_or_default(),"profileId":body.get("profileId").and_then(Value::as_str).unwrap_or_default(),"userId":body.get("userId").and_then(Value::as_str).unwrap_or_default(),"displayName":body.get("nameOnPlatform").and_then(Value::as_str).unwrap_or("Ubisoft"),"expiresAt":body.get("expiration").and_then(Value::as_str).and_then(|s| time::OffsetDateTime::parse(s, &time::format_description::well_known::Rfc3339).ok()).map(|d| d.unix_timestamp()*1000).unwrap_or(now_ms()+3600_000),"rememberMe":body.get("rememberMeTicket").and_then(Value::as_str).unwrap_or(remember)}),
    )
}

fn ubisoft_from_secret(secret: Value) -> Result<ConnectedStore, String> {
    let games = ubisoft_list(&secret)?;
    let display = secret
        .get("displayName")
        .and_then(Value::as_str)
        .unwrap_or("Ubisoft Connect")
        .to_owned();
    Ok(ConnectedStore {
        account: account("ubisoft", display, secret)?,
        games,
    })
}

fn ubisoft_list(secret: &Value) -> Result<Vec<Game>, String> {
    let ticket = secret
        .get("ticket")
        .and_then(Value::as_str)
        .ok_or("Falta la sesión de Ubisoft")?;
    let session = secret
        .get("sessionId")
        .and_then(Value::as_str)
        .unwrap_or("");
    let query = "query OwnedGames($limit: Int, $offset: Int) { viewer { games(limit: $limit, offset: $offset) { totalCount nodes { id spaceId name coverUrl backgroundUrl bannerUrl releaseDate } } } }";
    let http = client()?;
    let mut all = Vec::new();
    for offset in (0..5000).step_by(50) {
        let response = http.post("https://public-ubiservices.ubi.com/v1/profiles/me/uplay/graphql")
            .header("Ubi-AppId", "f68a4bb5-608a-4ff2-8123-be8ef797e0a6")
            .header("Ubi-RequestedPlatformType", "uplay")
            .header("Authorization", format!("Ubi_v1 t={ticket}"))
            .header("Ubi-SessionId", session)
            .json(&json!({"operationName":"OwnedGames","query":query,"variables":{"limit":50,"offset":offset}}))
            .send().map_err(|e| format!("No se pudo leer la biblioteca de Ubisoft: {e}"))?;
        if response.status() == reqwest::StatusCode::UNAUTHORIZED {
            return Err("La sesión de Ubisoft venció. Volvé a conectarla.".into());
        }
        let body: Value = response
            .error_for_status()
            .map_err(|e| format!("Ubisoft rechazó la biblioteca: {e}"))?
            .json()
            .map_err(|_| "Ubisoft devolvió una biblioteca ilegible".to_owned())?;
        let games = body
            .pointer("/data/viewer/games/nodes")
            .and_then(Value::as_array)
            .cloned()
            .ok_or("Ubisoft devolvió una página de biblioteca inválida")?;
        if games.is_empty() {
            break;
        }
        for item in games {
            let id = item
                .get("spaceId")
                .and_then(Value::as_str)
                .filter(|s| !s.is_empty())
                .or_else(|| item.get("id").and_then(Value::as_str))
                .unwrap_or("");
            let title = item
                .get("name")
                .and_then(Value::as_str)
                .unwrap_or("")
                .trim();
            if id.is_empty() || title.is_empty() {
                continue;
            }
            let cover = item.get("coverUrl").and_then(Value::as_str).unwrap_or("");
            let hero = item
                .get("backgroundUrl")
                .and_then(Value::as_str)
                .or_else(|| item.get("bannerUrl").and_then(Value::as_str))
                .unwrap_or(cover);
            all.push(game(
                "ubisoft",
                id,
                title,
                "https://store.ubisoft.com/",
                cover,
                hero,
            ));
        }
        if body
            .pointer("/data/viewer/games/totalCount")
            .and_then(Value::as_u64)
            .is_some_and(|n| offset as u64 + 50 >= n)
        {
            break;
        }
    }
    Ok(model::merge_games(all))
}

fn ubisoft_sync(secret: &str) -> Result<ConnectedStore, String> {
    let mut saved: Value = serde_json::from_str(secret)
        .map_err(|_| "La sesión de Ubisoft no se puede leer".to_owned())?;
    if saved
        .get("expiresAt")
        .and_then(Value::as_i64)
        .unwrap_or_default()
        < now_ms() + 600_000
    {
        let remember = saved
            .get("rememberMe")
            .and_then(Value::as_str)
            .unwrap_or_default();
        if !remember.is_empty() {
            saved = ubi_session(&format!("rm_v1 t={remember}"), remember)?;
        }
    }
    ubisoft_from_secret(saved)
}

fn gamejolt_connect(data: Value) -> Result<ConnectedStore, String> {
    let user = data
        .get("user")
        .ok_or("Game Jolt no confirmó una cuenta iniciada")?;
    let username = user
        .get("username")
        .and_then(Value::as_str)
        .unwrap_or_default();
    if username.is_empty() {
        return Err("Game Jolt no devolvió el nombre de usuario".into());
    }
    let display = user
        .get("display_name")
        .and_then(Value::as_str)
        .filter(|s| !s.is_empty())
        .unwrap_or(username)
        .to_owned();
    let secret = json!({"username":username,"displayName":display});
    let games = gamejolt_games(
        data.get("games")
            .and_then(Value::as_array)
            .cloned()
            .unwrap_or_default(),
    );
    Ok(ConnectedStore {
        account: account("gamejolt", display, secret)?,
        games,
    })
}

fn gamejolt_games(rows: Vec<Value>) -> Vec<Game> {
    rows.into_iter()
        .filter_map(|row| {
            let id = row.get("id")?.as_i64()?.to_string();
            let title = row.get("title")?.as_str()?.trim();
            if title.is_empty() {
                return None;
            }
            let slug = row.get("slug").and_then(Value::as_str).unwrap_or("game");
            let cover = row
                .pointer("/thumbnail_media_item/img_url")
                .and_then(Value::as_str)
                .or_else(|| row.get("img_thumbnail").and_then(Value::as_str))
                .unwrap_or("");
            let hero = row
                .pointer("/header_media_item/img_url")
                .and_then(Value::as_str)
                .unwrap_or(cover);
            Some(game(
                "gamejolt",
                &id,
                title,
                &format!("https://gamejolt.com/games/{slug}/{id}"),
                cover,
                hero,
            ))
        })
        .collect()
}

fn itch_connect(key: &str) -> Result<ConnectedStore, String> {
    let key = key.trim();
    if key.len() < 40 || !key.bytes().all(|b| b.is_ascii_alphanumeric()) {
        return Err("Pegá una clave API válida de itch.io.".into());
    }
    let response = client()?
        .get("https://api.itch.io/profile")
        .bearer_auth(key)
        .send()
        .map_err(|e| format!("No se pudo verificar itch.io: {e}"))?;
    if !response.status().is_success() {
        return Err("itch.io rechazó la clave. Revisá la clave y probá de nuevo.".into());
    }
    let profile: Value = response
        .json()
        .map_err(|_| "itch.io devolvió un perfil ilegible".to_owned())?;
    let user = profile
        .get("user")
        .ok_or("itch.io no devolvió el perfil de la cuenta")?;
    let display = user
        .get("display_name")
        .and_then(Value::as_str)
        .filter(|s| !s.is_empty())
        .or_else(|| user.get("username").and_then(Value::as_str))
        .unwrap_or("itch.io")
        .to_owned();
    let secret = json!({"access_token":key,"username":user.get("username"),"displayName":display});
    let games = itch_list(key)?;
    Ok(ConnectedStore {
        account: account("itch", display, secret)?,
        games,
    })
}

fn itch_list(key: &str) -> Result<Vec<Game>, String> {
    let mut out = Vec::new();
    let http = client()?;
    for page in 1..=20 {
        let response = http
            .get("https://api.itch.io/profile/owned-keys")
            .query(&[("page", page.to_string()), ("per_page", "500".to_owned())])
            .bearer_auth(key)
            .send()
            .map_err(|e| format!("No se pudo leer la biblioteca de itch.io: {e}"))?;
        if response.status() == reqwest::StatusCode::UNAUTHORIZED {
            return Err("La clave de itch.io venció o fue revocada.".into());
        }
        let body: Value = response
            .error_for_status()
            .map_err(|e| format!("itch.io rechazó la biblioteca: {e}"))?
            .json()
            .map_err(|_| "itch.io devolvió una biblioteca ilegible".to_owned())?;
        let rows = body
            .get("owned_keys")
            .and_then(Value::as_array)
            .or_else(|| body.get("ownedKeys").and_then(Value::as_array))
            .cloned()
            .unwrap_or_default();
        if rows.is_empty() {
            break;
        }
        for entry in rows {
            let Some(game_obj) = entry.get("game") else {
                continue;
            };
            let id = game_obj
                .get("id")
                .and_then(Value::as_i64)
                .unwrap_or_default()
                .to_string();
            let title = game_obj
                .get("title")
                .and_then(Value::as_str)
                .unwrap_or("")
                .trim();
            if id == "0" || title.is_empty() {
                continue;
            }
            let url = game_obj.get("url").and_then(Value::as_str).unwrap_or("");
            let cover = game_obj
                .get("cover_url")
                .and_then(Value::as_str)
                .or_else(|| game_obj.get("still_cover_url").and_then(Value::as_str))
                .unwrap_or("");
            out.push(game(
                "itch",
                &id,
                title,
                url,
                cover,
                game_obj
                    .get("cover_url")
                    .and_then(Value::as_str)
                    .unwrap_or(cover),
            ));
        }
    }
    Ok(model::merge_games(out))
}

fn itch_sync(secret: &str) -> Result<ConnectedStore, String> {
    let saved: Value = serde_json::from_str(secret)
        .map_err(|_| "La sesión de itch.io no se puede leer".to_owned())?;
    let key = saved
        .get("access_token")
        .and_then(Value::as_str)
        .ok_or("Falta la clave de itch.io")?;
    let games = itch_list(key)?;
    let display = saved
        .get("displayName")
        .and_then(Value::as_str)
        .unwrap_or("itch.io")
        .to_owned();
    Ok(ConnectedStore {
        account: account("itch", display, saved)?,
        games,
    })
}

fn rockstar_connect(raw_url: &str, extra: &str, data: Value) -> Result<ConnectedStore, String> {
    let callback = url::Url::parse(raw_url)
        .map_err(|_| "Rockstar no terminó el inicio de sesión".to_owned())?;
    if callback
        .query_pairs()
        .any(|(key, value)| key == "cancel" && value == "1")
    {
        return Err("Se canceló el inicio de sesión de Rockstar.".into());
    }
    let fingerprint = data
        .get("fingerprint")
        .cloned()
        .or_else(|| serde_json::from_str::<Value>(extra).ok())
        .ok_or("No se pudo validar el dispositivo para Rockstar")?;
    let token = if let Some(gateway) = data.get("gateway").filter(|value| value.is_object()) {
        gateway.clone()
    } else {
        let code = callback
            .query_pairs()
            .find(|(key, _)| key == "code")
            .map(|(_, value)| value.into_owned())
            .filter(|value| !value.is_empty())
            .ok_or(
                "Rockstar no devolvió el código de inicio de sesión. Volvé a conectar la cuenta.",
            )?;
        let state = json!({"fp":fingerprint});
        let mut request = client()?.get("https://rgl.rockstargames.com/api/connect/gateway")
            .query(&[("code", code.as_str()), ("fingerprint", state.to_string().as_str())])
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36")
            .header("Accept", "application/json")
            .header("X-Requested-With", "XMLHttpRequest")
            .header("Origin", "https://rgl.rockstargames.com")
            .header("Referer", "https://rgl.rockstargames.com/");
        if let Some(cookie) = data
            .get("cookies")
            .and_then(Value::as_str)
            .filter(|value| !value.is_empty())
        {
            request = request.header(reqwest::header::COOKIE, cookie);
        }
        let response = request
            .send()
            .map_err(|e| format!("No se pudo validar la cuenta de Rockstar: {e}"))?;
        if !response.status().is_success() {
            return Err(format!(
                "Rockstar rechazó el inicio de sesión (HTTP {})",
                response.status()
            ));
        }
        response.json().map_err(|_| {
            "Rockstar devolvió una respuesta de inicio de sesión ilegible".to_owned()
        })?
    };
    let access = token
        .get("ScAuthToken")
        .and_then(Value::as_str)
        .filter(|v| !v.is_empty())
        .ok_or("Rockstar no entregó una sesión utilizable")?;
    let secret = json!({
        "accessToken":access,
        "rememberedMachineToken":token.get("RememberedMachineToken").and_then(Value::as_str).unwrap_or(""),
        "fingerprint":fingerprint,
        "displayName":"Rockstar Games"
    });
    rockstar_from_secret(secret)
}

fn rockstar_from_secret(mut secret: Value) -> Result<ConnectedStore, String> {
    let ticket = rockstar_ticket(&secret)?;
    if let Some(name) = ticket
        .get("nickname")
        .and_then(Value::as_str)
        .filter(|value| !value.is_empty())
    {
        secret["displayName"] = json!(name);
    }
    let display = secret
        .get("displayName")
        .and_then(Value::as_str)
        .unwrap_or("Rockstar Games")
        .to_owned();
    let games = rockstar_library(
        ticket
            .get("ticket")
            .and_then(Value::as_str)
            .ok_or("Rockstar no entregó un ticket de sesión")?,
    )?;
    Ok(ConnectedStore {
        account: account("rockstar", display, secret)?,
        games,
    })
}

fn rockstar_ticket(secret: &Value) -> Result<Value, String> {
    let access = secret
        .get("accessToken")
        .and_then(Value::as_str)
        .ok_or("Falta la sesión de Rockstar")?;
    let remembered = secret
        .get("rememberedMachineToken")
        .and_then(Value::as_str)
        .unwrap_or("");
    let fingerprint = secret
        .get("fingerprint")
        .cloned()
        .unwrap_or_else(|| json!({}));
    let state = json!({"fp":fingerprint});
    let response = client()?.post("https://rgl-prod.ros.rockstargames.com/launcher/11/launcherservices/auth.asmx/CreateTicketScAuthToken2")
        .header("Content-Type", "application/x-www-form-urlencoded; charset=utf-8")
        .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36")
        .header("SCS-MFAMachineFingerPrint", state.to_string())
        .form(&[("ticket", ""), ("platformName", "pcros"), ("scAuthToken", access), ("rememberedMachineToken", remembered)])
        .send().map_err(|e| format!("No se pudo abrir la sesión de Rockstar: {e}"))?;
    let status = response.status();
    let xml = response
        .text()
        .map_err(|_| "Rockstar devolvió un ticket ilegible".to_owned())?;
    if !status.is_success() {
        return Err(format!("Rockstar rechazó la sesión (HTTP {status})"));
    }
    let document = roxmltree::Document::parse(&xml)
        .map_err(|_| "Rockstar devolvió un ticket XML ilegible".to_owned())?;
    let root = document.root_element();
    if xml_value(root, "Status") != Some("1") {
        return Err("La sesión de Rockstar venció o fue rechazada. Volvé a conectarla.".into());
    }
    let ticket = xml_value(root, "Ticket")
        .filter(|value| !value.is_empty())
        .ok_or("Rockstar no entregó un ticket de sesión")?;
    let account_node = root
        .descendants()
        .find(|node| node.is_element() && node.tag_name().name() == "RockstarAccount");
    Ok(json!({
        "ticket":ticket,
        "nickname":account_node.and_then(|node| xml_value(node,"Nickname")).unwrap_or_default(),
        "rockstarId":account_node.and_then(|node| xml_value(node,"RockstarId")).unwrap_or_default()
    }))
}

fn rockstar_library(ticket: &str) -> Result<Vec<Game>, String> {
    let response = client()?.post("https://rgl-prod.ros.rockstargames.com/launcher/11/launcherservices/app.asmx/GetAppsAndPackages")
        .header("Content-Type", "application/x-www-form-urlencoded; charset=utf-8")
        .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36")
        .form(&[("ticket", ticket)])
        .send().map_err(|e| format!("No se pudo leer la biblioteca de Rockstar: {e}"))?;
    let status = response.status();
    let xml = response
        .text()
        .map_err(|_| "Rockstar devolvió una biblioteca ilegible".to_owned())?;
    if !status.is_success() {
        return Err(format!("Rockstar rechazó la biblioteca (HTTP {status})"));
    }
    let document = roxmltree::Document::parse(&xml)
        .map_err(|_| "Rockstar devolvió una biblioteca XML ilegible".to_owned())?;
    let root = document.root_element();
    if xml_value(root, "Status") != Some("1") {
        return Err("Rockstar no autorizó leer la biblioteca".into());
    }
    let mut games = Vec::new();
    let mut seen = HashSet::new();
    for node in root
        .descendants()
        .filter(|node| node.is_element() && node.tag_name().name() == "PackageJson")
    {
        let Some(package) = node
            .text()
            .and_then(|text| serde_json::from_str::<Value>(text).ok())
        else {
            continue;
        };
        if package
            .get("isFeatured")
            .and_then(Value::as_bool)
            .unwrap_or(false)
        {
            continue;
        }
        let Some(package_title) = package
            .get("title")
            .and_then(Value::as_str)
            .map(str::trim)
            .filter(|v| !v.is_empty())
        else {
            continue;
        };
        for (id, title, appid) in rockstar_titles(package_title) {
            if !seen.insert(id.to_owned()) {
                continue;
            }
            let store_url = "https://store.rockstargames.com/";
            let cover = if appid.is_empty() {
                String::new()
            } else {
                format!(
                    "https://cdn.cloudflare.steamstatic.com/steam/apps/{appid}/library_600x900.jpg"
                )
            };
            let hero = if appid.is_empty() {
                String::new()
            } else {
                format!(
                    "https://cdn.cloudflare.steamstatic.com/steam/apps/{appid}/library_hero.jpg"
                )
            };
            games.push(game("rockstar", id, title, store_url, &cover, &hero));
        }
    }
    games.sort_by(|a, b| a.title.to_lowercase().cmp(&b.title.to_lowercase()));
    Ok(games)
}

fn rockstar_sync(secret: &str) -> Result<ConnectedStore, String> {
    let mut saved: Value = serde_json::from_str(secret)
        .map_err(|_| "La sesión de Rockstar no se puede leer".to_owned())?;
    let ticket = rockstar_ticket(&saved)?;
    if let Some(name) = ticket
        .get("nickname")
        .and_then(Value::as_str)
        .filter(|value| !value.is_empty())
    {
        saved["displayName"] = json!(name);
    }
    let display = saved
        .get("displayName")
        .and_then(Value::as_str)
        .unwrap_or("Rockstar Games")
        .to_owned();
    let games = rockstar_library(
        ticket
            .get("ticket")
            .and_then(Value::as_str)
            .ok_or("Rockstar no entregó un ticket de sesión")?,
    )?;
    Ok(ConnectedStore {
        account: account("rockstar", display, saved)?,
        games,
    })
}

fn xml_value<'a, 'input>(node: roxmltree::Node<'a, 'input>, name: &str) -> Option<&'a str> {
    node.descendants()
        .find(|child| child.is_element() && child.tag_name().name() == name)?
        .text()
        .map(str::trim)
}

fn rockstar_titles(package: &str) -> Vec<(&'static str, &'static str, &'static str)> {
    let name = package.to_ascii_lowercase();
    let name = match name.as_str() {
        "rdr2_sp" | "rdr2_rdo" | "rdr2_sp_rgl" | "rdr2_sp_steam" | "rdr2_sp_epic" => "rdr2",
        "gtatrilogy" => "gtatrilogy",
        _ => name.as_str(),
    };
    if name == "gtatrilogy" {
        return vec![
            (
                "gta3unreal",
                "Grand Theft Auto III – The Definitive Edition",
                "1546970",
            ),
            (
                "gtavcunreal",
                "Grand Theft Auto: Vice City – The Definitive Edition",
                "1546990",
            ),
            (
                "gtasaunreal",
                "Grand Theft Auto: San Andreas – The Definitive Edition",
                "1547000",
            ),
        ];
    }
    match name {
        "gta5_gen9" => vec![("gta5_gen9", "Grand Theft Auto V Enhanced", "3240220")],
        "gta5" => vec![("gta5", "Grand Theft Auto V Legacy", "271590")],
        "rdr2" => vec![("rdr2", "Red Dead Redemption 2", "1174180")],
        "rdr" => vec![("rdr", "Red Dead Redemption", "2668510")],
        "gta4" => vec![("gta4", "Grand Theft Auto IV: The Complete Edition", "12210")],
        "lanoire" => vec![("lanoire", "L.A. Noire", "110800")],
        "lanoirevr" => vec![("lanoirevr", "L.A. Noire: The VR Case Files", "722230")],
        "mp3" => vec![("mp3", "Max Payne 3", "204100")],
        "bully" => vec![("bully", "Bully: Scholarship Edition", "12200")],
        "gta3unreal" => vec![(
            "gta3unreal",
            "Grand Theft Auto III – The Definitive Edition",
            "1546970",
        )],
        "gtavcunreal" => vec![(
            "gtavcunreal",
            "Grand Theft Auto: Vice City – The Definitive Edition",
            "1546990",
        )],
        "gtasaunreal" => vec![(
            "gtasaunreal",
            "Grand Theft Auto: San Andreas – The Definitive Edition",
            "1547000",
        )],
        "gtasa" => vec![("gtasa", "Grand Theft Auto: San Andreas", "12120")],
        "gtavc" => vec![("gtavc", "Grand Theft Auto: Vice City", "12110")],
        "gta3" => vec![("gta3", "Grand Theft Auto III", "12100")],
        _ => Vec::new(),
    }
}

fn now_ms() -> i64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_millis() as i64)
        .unwrap_or_default()
}

pub mod amazon {
    pub mod proto {
        include!(concat!(
            env!("CARGO_MANIFEST_DIR"),
            "/../app/src/main/rust/stores/src/amazon/proto.rs"
        ));
    }
    pub mod net {
        include!(concat!(
            env!("CARGO_MANIFEST_DIR"),
            "/../app/src/main/rust/stores/src/amazon/net.rs"
        ));
    }
    pub mod auth {
        include!(concat!(env!("CARGO_MANIFEST_DIR"), "/src/amazon_auth.rs"));
    }
    pub mod distribution {
        include!(concat!(
            env!("CARGO_MANIFEST_DIR"),
            "/../app/src/main/rust/stores/src/amazon/distribution.rs"
        ));
    }
    pub mod library {
        include!(concat!(
            env!("CARGO_MANIFEST_DIR"),
            "/../app/src/main/rust/stores/src/amazon/library.rs"
        ));
    }
}
