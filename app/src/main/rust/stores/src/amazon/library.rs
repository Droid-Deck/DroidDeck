use std::collections::HashMap;

use serde_json::{json, Value};
use sha2::{Digest, Sha256};

use super::distribution::{self, Failure};
use super::net::{self, str_at, str_of};

pub const CLIENT_ID: &str = "Sonic";
pub const KEY_ID: &str = "d5dc8b8b-86c8-4fc4-ae93-18c0def5314d";
pub const PAGE_SIZE: u32 = 50;
const MAX_PAGES: usize = 1000;

#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct Game {
    pub product_id: String,
    pub entitlement_id: String,
    pub title: String,
    pub sku: String,
    pub product_type: String,
    pub parent_product_id: String,
    pub art: String,
    pub hero: String,
    pub developer: String,
    pub publisher: String,
}

impl Game {
    pub fn is_dlc(&self) -> bool {
        (!self.product_type.is_empty() && !self.product_type.eq_ignore_ascii_case("GAME"))
            || !self.parent_product_id.is_empty()
    }

    pub fn to_json(&self) -> Value {
        json!({
            "productId": self.product_id,
            "entitlementId": self.entitlement_id,
            "title": self.title,
            "sku": self.sku,
            "art": self.art,
            "hero": self.hero,
            "developer": self.developer,
            "publisher": self.publisher,
            "dlc": self.is_dlc(),
            "parentProductId": self.parent_product_id,
        })
    }
}

pub fn hardware_hash(device_serial: &str) -> String {
    net::hex_upper(&Sha256::digest(device_serial.as_bytes()))
}

fn first<'v>(product: &'v Value, paths: &[&[&str]]) -> &'v str {
    paths.iter().map(|p| str_at(product, p)).find(|s| !s.is_empty()).unwrap_or("")
}

pub fn read_entitlement(e: &Value) -> Option<Game> {
    let product = e.get("product").filter(|p| p.is_object())?;
    let product_id = str_of(product, "id");
    if product_id.is_empty() {
        return None;
    }
    let title = str_of(product, "title");
    Some(Game {
        product_id: product_id.to_string(),
        entitlement_id: str_of(e, "id").to_string(),
        title: if title.is_empty() { "Unknown".to_string() } else { title.to_string() },
        sku: str_of(product, "sku").to_string(),
        product_type: first(product, &[&["productType"], &["type"], &["productDetail", "details", "productType"]]).to_string(),
        parent_product_id: first(
            product,
            &[&["parentProductId"], &["baseProductId"], &["parentId"], &["productDetail", "details", "parentProductId"]],
        )
        .to_string(),
        art: first(product, &[&["productDetail", "iconUrl"], &["productDetail", "details", "pgCrownImageUrl"]]).to_string(),
        hero: first(
            product,
            &[
                &["productDetail", "details", "backgroundUrl2"],
                &["productDetail", "details", "backgroundUrl1"],
                &["productDetail", "details", "pgCrownImageUrl"],
            ],
        )
        .to_string(),
        developer: str_at(product, &["productDetail", "details", "developer"]).to_string(),
        publisher: str_at(product, &["productDetail", "details", "publisher"]).to_string(),
    })
}

pub fn read_page(body: &Value) -> (Vec<Game>, Option<String>) {
    let games = body
        .get("entitlements")
        .and_then(|e| e.as_array())
        .map(|a| a.iter().filter_map(read_entitlement).collect())
        .unwrap_or_default();
    let next = Some(str_of(body, "nextToken")).filter(|t| !t.is_empty()).map(str::to_string);
    (games, next)
}

pub fn dedupe(games: Vec<Game>) -> Vec<Game> {
    let mut out: Vec<Game> = Vec::with_capacity(games.len());
    let mut index: HashMap<String, usize> = HashMap::new();
    for g in games {
        match index.get(&g.product_id) {
            Some(&i) => out[i] = g,
            None => {
                index.insert(g.product_id.clone(), out.len());
                out.push(g);
            }
        }
    }
    out
}

pub fn visible(games: Vec<Game>) -> Vec<Game> {
    let kept: Vec<Game> = games.iter().filter(|g| !(g.is_dlc() && !g.parent_product_id.is_empty())).cloned().collect();
    let mut out = if kept.is_empty() { games } else { kept };
    out.sort_by_key(|g| g.title.to_lowercase());
    out
}

pub fn page_body(device_serial: &str, next: Option<&str>) -> Value {
    json!({
        "Operation": "GetEntitlements",
        "clientId": CLIENT_ID,
        "syncPoint": null,
        "nextToken": next,
        "maxResults": PAGE_SIZE,
        "productIdFilter": null,
        "keyId": KEY_ID,
        "hardwareHash": hardware_hash(device_serial),
    })
}

pub fn list(access_token: &str, device_serial: &str) -> Result<Vec<Game>, Failure> {
    let mut all = Vec::new();
    let mut next: Option<String> = None;
    for _ in 0..MAX_PAGES {
        let body = page_body(device_serial, next.as_deref());
        let answer = distribution::call(
            distribution::ENTITLEMENTS_URL,
            distribution::ENTITLEMENTS_TARGET,
            access_token,
            &body,
        )?;
        let (games, cursor) = read_page(&answer);
        all.extend(games);
        match cursor {
            Some(c) if Some(&c) != next.as_ref() => next = Some(c),
            Some(_) => return Err(Failure::Other("Amazon repeated a library page cursor".into())),
            None => return Ok(dedupe(all)),
        }
    }
    Err(Failure::Other("the Amazon library has too many pages".into()))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn game(id: &str, title: &str, kind: &str, parent: &str) -> Game {
        Game {
            product_id: id.into(),
            title: title.into(),
            product_type: kind.into(),
            parent_product_id: parent.into(),
            ..Default::default()
        }
    }

    #[test]
    fn hardware_hash_is_upper_hex_sha256() {
        assert_eq!(hardware_hash("abc"), "BA7816BF8F01CFEA414140DE5DAE2223B00361A396177A9CB410FF61F20015AD");
    }

    #[test]
    fn entitlement_fields_and_fallbacks() {
        let e = json!({"id":"ent-1","product":{"id":"amzn1.adg.product.x","title":"Game","sku":"SKU1",
            "productDetail":{"iconUrl":"","details":{"pgCrownImageUrl":"crown","backgroundUrl1":"bg1",
            "developer":"Dev","publisher":"Pub","productType":"GAME"}}}});
        let g = read_entitlement(&e).unwrap();
        assert_eq!(g.entitlement_id, "ent-1");
        assert_eq!(g.art, "crown");
        assert_eq!(g.hero, "bg1");
        assert_eq!(g.product_type, "GAME");
        assert!(!g.is_dlc());
        let untitled = read_entitlement(&json!({"product":{"id":"p","type":"DLC","baseProductId":"q"}})).unwrap();
        assert_eq!(untitled.title, "Unknown");
        assert_eq!(untitled.entitlement_id, "");
        assert_eq!(untitled.parent_product_id, "q");
        assert!(untitled.is_dlc());
        assert!(read_entitlement(&json!({"id":"x"})).is_none());
        assert!(read_entitlement(&json!({"id":"x","product":{"id":""}})).is_none());
    }

    #[test]
    fn pages_and_cursors() {
        let (games, next) = read_page(&json!({"entitlements":[{"product":{"id":"a"}},{"product":{}}],"nextToken":"n2"}));
        assert_eq!(games.len(), 1);
        assert_eq!(next.as_deref(), Some("n2"));
        assert_eq!(read_page(&json!({"nextToken":null})).1, None);
        assert_eq!(read_page(&json!({"nextToken":""})).1, None);
        assert!(read_page(&json!({})).0.is_empty());
        let first = page_body("SERIAL", None);
        assert!(first["nextToken"].is_null());
        assert_eq!(first["maxResults"], 50);
        assert_eq!(page_body("SERIAL", Some("n2"))["nextToken"], "n2");
    }

    #[test]
    fn duplicates_and_visibility() {
        let games = dedupe(vec![game("a", "b-old", "", ""), game("c", "zeta", "", ""), game("a", "Beta", "", "")]);
        assert_eq!(games.len(), 2);
        assert_eq!(games[0].title, "Beta");
        let shown = visible(vec![
            game("1", "zed", "GAME", ""),
            game("2", "alpha", "", ""),
            game("3", "addon", "DLC", "1"),
            game("4", "soundtrack", "DLC", ""),
            game("5", "Bravo", "game", ""),
        ]);
        let titles: Vec<&str> = shown.iter().map(|g| g.title.as_str()).collect();
        assert_eq!(titles, vec!["alpha", "Bravo", "soundtrack", "zed"]);
        let only_addons = visible(vec![game("3", "addon", "DLC", "1")]);
        assert_eq!(only_addons.len(), 1);
    }
}
