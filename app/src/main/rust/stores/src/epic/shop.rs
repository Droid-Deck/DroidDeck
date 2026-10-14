use std::collections::HashSet;
use std::time::Duration;

use serde_json::{json, Value};

use super::net::{self, Body, Request};

const GRAPHQL_URL: &str = "https://store.epicgames.com/graphql";
const PROMOTIONS_URL: &str = "https://store-site-backend-static-ipv4.ak.epicgames.com/freeGamesPromotions";
const SHELF_COUNT: u32 = 24;

#[derive(Clone, Copy)]
pub enum Shelf {
    New,
    Deals,
    Free,
    Trending,
}

fn country_code(raw: &str) -> String {
    let c = raw.trim().to_ascii_uppercase();
    if c.len() == 2 && c.bytes().all(|b| b.is_ascii_uppercase()) {
        c
    } else {
        "US".to_string()
    }
}

fn now_stamp() -> String {
    let secs = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_secs() as i64)
        .unwrap_or(0);
    let days = secs.div_euclid(86_400);
    let rem = secs.rem_euclid(86_400);
    let (y, m, d) = civil_from_days(days);
    format!("{y:04}-{m:02}-{d:02}T{:02}:{:02}:{:02}.000Z", rem / 3600, rem % 3600 / 60, rem % 60)
}

fn civil_from_days(z: i64) -> (i64, i64, i64) {
    let z = z + 719_468;
    let era = if z >= 0 { z } else { z - 146_096 } / 146_097;
    let doe = z - era * 146_097;
    let yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let mp = (5 * doy + 2) / 153;
    let d = doy - (153 * mp + 2) / 5 + 1;
    let m = if mp < 10 { mp + 3 } else { mp - 9 };
    (yoe + era * 400 + if m <= 2 { 1 } else { 0 }, m, d)
}

pub fn shelf_query(shelf: Shelf, country: &str, now: &str) -> String {
    let (sort_by, sort_dir, extra) = match shelf {
        Shelf::New => ("releaseDate", "DESC", format!("releaseDate: \"[,{now}]\"")),
        Shelf::Deals => ("currentPrice", "ASC", "onSale: true".to_string()),
        Shelf::Free => ("relevancy", "DESC", "freeGame: true".to_string()),
        Shelf::Trending => ("relevancy", "DESC", format!("releaseDate: \"[,{now}]\"")),
    };
    format!(
        "query {{ Catalog {{ searchStore(category: \"games/edition/base\", count: {SHELF_COUNT}, \
country: \"{country}\", locale: \"en-US\", sortBy: \"{sort_by}\", sortDir: \"{sort_dir}\", \
allowCountries: \"{country}\", {extra}) {{ elements {{ title id namespace description effectiveDate \
releaseDate offerType productSlug urlSlug items {{ id namespace }} keyImages {{ type url }} \
seller {{ name }} tags {{ name }} customAttributes {{ key value }} \
catalogNs {{ mappings(pageType: \"productHome\") {{ pageSlug pageType }} }} \
price(country: \"{country}\") {{ totalPrice {{ discountPrice originalPrice discount currencyCode \
fmtPrice(locale: \"en-US\") {{ originalPrice discountPrice }} }} }} }} paging {{ total }} }} }} }}"
    )
}

fn elements(answer: &Value) -> Vec<Value> {
    answer
        .pointer("/data/Catalog/searchStore/elements")
        .and_then(|e| e.as_array())
        .cloned()
        .unwrap_or_default()
}

fn key_image(el: &Value, order: &[&[&str]]) -> String {
    let list = el.get("keyImages").and_then(|v| v.as_array()).cloned().unwrap_or_default();
    for group in order {
        if let Some(img) = list
            .iter()
            .find(|i| group.contains(&net::str_of(i, "type")) && !net::str_of(i, "url").is_empty())
        {
            return net::str_of(img, "url").to_string();
        }
    }
    String::new()
}

fn custom_attribute(el: &Value, key: &str) -> String {
    el.get("customAttributes")
        .and_then(|a| a.as_array())
        .and_then(|a| a.iter().find(|kv| net::str_of(kv, "key") == key))
        .map(|kv| net::str_of(kv, "value").trim().to_string())
        .unwrap_or_default()
}

fn store_slug(el: &Value) -> String {
    let mapped = el
        .pointer("/catalogNs/mappings")
        .and_then(|m| m.as_array())
        .and_then(|m| m.iter().find(|x| net::str_of(x, "pageType") == "productHome"))
        .map(|x| net::str_of(x, "pageSlug").to_string())
        .filter(|s| !s.is_empty());
    mapped
        .or_else(|| {
            let s = net::str_of(el, "productSlug");
            Some(s.trim_end_matches("/home").to_string()).filter(|s| !s.is_empty() && s != "null")
        })
        .unwrap_or_default()
}

pub fn item_of(el: &Value) -> Option<Value> {
    let id = net::str_of(el, "id");
    let title = net::str_of(el, "title");
    if id.is_empty() || title.is_empty() {
        return None;
    }
    let total = el.pointer("/price/totalPrice");
    let orig = total.and_then(|t| t.get("originalPrice")).and_then(|v| v.as_i64());
    let disc = total.and_then(|t| t.get("discountPrice")).and_then(|v| v.as_i64());
    let fmt = |k: &str| {
        total
            .and_then(|t| t.get("fmtPrice"))
            .map(|f| net::str_of(f, k).trim().to_string())
            .unwrap_or_default()
    };
    let has_price = orig.is_some() && disc.is_some();
    let free = disc == Some(0);
    let discount = match (orig, disc) {
        (Some(o), Some(d)) if o > 0 && d < o => ((o - d) * 100 / o) as i32,
        _ => 0,
    };
    let shown = if free {
        "Free".to_string()
    } else {
        let d = fmt("discountPrice");
        if !d.is_empty() && d != "0" {
            d
        } else {
            fmt("originalPrice")
        }
    };
    let names: Vec<String> = el
        .get("tags")
        .and_then(|t| t.as_array())
        .map(|a| a.iter().map(|t| net::str_of(t, "name").to_string()).filter(|n| !n.is_empty()).collect())
        .unwrap_or_default();
    let tag_line = names.iter().filter(|n| *n != "Windows").take(3).cloned().collect::<Vec<_>>().join(", ");
    let items: Vec<String> = el
        .get("items")
        .and_then(|i| i.as_array())
        .map(|a| a.iter().map(|i| net::str_of(i, "id").to_string()).filter(|s| !s.is_empty()).collect())
        .unwrap_or_default();
    let slug = store_slug(el);
    let url = if slug.is_empty() {
        format!("https://store.epicgames.com/en-US/browse?q={}", net::percent_encode(title))
    } else {
        format!("https://store.epicgames.com/en-US/p/{slug}")
    };
    let publisher = Some(custom_attribute(el, "publisherName"))
        .filter(|p| !p.is_empty())
        .unwrap_or_else(|| el.get("seller").map(|s| net::str_of(s, "name").to_string()).unwrap_or_default());
    let release = Some(net::str_of(el, "releaseDate"))
        .filter(|s| !s.is_empty())
        .unwrap_or_else(|| net::str_of(el, "effectiveDate"));
    let mut extra = json!({ "namespace": net::str_of(el, "namespace") });
    if !items.is_empty() {
        extra["items"] = json!(items.join(","));
    }
    if !slug.is_empty() {
        extra["slug"] = json!(slug);
    }
    Some(json!({
        "id": id,
        "title": title,
        "image": key_image(el, &[&["DieselGameBox"], &["OfferImageWide", "DieselStoreFrontWide"], &["Thumbnail"]]),
        "tall": key_image(el, &[&["DieselGameBoxTall"], &["OfferImageTall", "DieselStoreFrontTall"], &["Thumbnail"]]),
        "tags": tag_line,
        "free": free,
        "hasPrice": has_price,
        "final": shown,
        "orig": if discount > 0 { fmt("originalPrice") } else { String::new() },
        "disc": discount,
        "url": url,
        "dev": custom_attribute(el, "developerName"),
        "pub": publisher,
        "rel": release,
        "desc": net::str_of(el, "description"),
        "extra": extra,
        "allTags": names,
    }))
}

pub fn free_now(el: &Value) -> Option<Value> {
    let offer = el.pointer("/promotions/promotionalOffers/0/promotionalOffers/0")?;
    let pct = offer.pointer("/discountSetting/discountPercentage")?.as_f64()?;
    if pct != 0.0 {
        return None;
    }
    let mut item = item_of(el)?;
    let until: String = net::str_of(offer, "endDate").chars().take(10).collect();
    item["free"] = json!(true);
    item["disc"] = json!(100);
    item["final"] = json!("Free");
    if !until.is_empty() {
        item["tags"] = json!(format!("Free until {until}"));
    }
    Some(item)
}

fn post_query(query: String) -> Vec<Value> {
    let body = json!({ "query": query, "variables": {} }).to_string();
    match net::send(Request::post(GRAPHQL_URL, Body::Json(body)).read_timeout(Duration::from_secs(25))) {
        Ok(reply) if reply.ok() => reply.json().map(|a| elements(&a)).unwrap_or_default(),
        _ => Vec::new(),
    }
}

fn promotions(country: &str) -> Vec<Value> {
    let url = format!("{PROMOTIONS_URL}?locale=en-US&country={country}&allowCountries={country}");
    match net::send(Request::get(&url).read_timeout(Duration::from_secs(25))) {
        Ok(reply) if reply.ok() => reply.json().map(|a| elements(&a)).unwrap_or_default(),
        _ => Vec::new(),
    }
}

fn unique(items: impl IntoIterator<Item = Value>) -> Vec<Value> {
    let mut seen = HashSet::new();
    items.into_iter().filter(|i| seen.insert(net::str_of(i, "id").to_string())).collect()
}

const MATURE_TAGS: [&str; 8] = ["mature", "nsfw", "adult", "adult only", "adults only", "nudity", "sexual content", "sexual-content"];

fn mature_tags(item: &Value) -> bool {
    item["allTags"].as_array().is_some_and(|tags| {
        tags.iter().filter_map(|t| t.as_str()).any(|t| MATURE_TAGS.contains(&t.trim().to_lowercase().as_str()))
    })
}

pub fn wall_art(promotion_list: &[Value]) -> Vec<String> {
    let mut seen = HashSet::new();
    promotion_list
        .iter()
        .filter_map(item_of)
        .filter(|i| !mature_tags(i))
        .map(|i| {
            let tall = net::str_of(&i, "tall");
            if tall.is_empty() { net::str_of(&i, "image").to_string() } else { tall.to_string() }
        })
        .filter(|u| !u.is_empty() && seen.insert(u.clone()))
        .collect()
}

pub fn shelves(country: &str) -> Value {
    let country = country_code(country);
    let now = now_stamp();
    let shelf = |s: Shelf| -> Vec<Value> {
        post_query(shelf_query(s, &country, &now)).iter().filter_map(item_of).collect()
    };
    let new = unique(shelf(Shelf::New));
    let deals = unique(shelf(Shelf::Deals).into_iter().filter(|i| i["disc"].as_i64().unwrap_or(0) > 0));
    let promotion_list = promotions(&country);
    let art = wall_art(&promotion_list);
    let promos: Vec<Value> = promotion_list.iter().filter_map(free_now).collect();
    let free = unique(promos.into_iter().chain(shelf(Shelf::Free)));
    let trending = unique(shelf(Shelf::Trending));
    json!({ "new": new, "deals": deals, "free": free, "trending": trending, "art": art })
}

#[cfg(test)]
mod tests {
    use super::*;

    fn element() -> Value {
        json!({
            "id": "offer1", "title": "Fig & Co", "namespace": "ns",
            "description": "<p>Hi</p>", "releaseDate": "2024-01-02T00:00:00.000Z",
            "productSlug": "fig/home", "items": [{"id": "cat1"}, {"id": "cat2"}],
            "keyImages": [{"type": "Thumbnail", "url": "t"}, {"type": "OfferImageWide", "url": "w"}, {"type": "DieselStoreFrontTall", "url": "tl"}],
            "seller": {"name": "Seller"},
            "tags": [{"name": "Windows"}, {"name": "Action"}, {"name": "RPG"}, {"name": "Indie"}, {"name": "Mature"}],
            "customAttributes": [{"key": "developerName", "value": "Dev"}],
            "catalogNs": {"mappings": []},
            "price": {"totalPrice": {"originalPrice": 2000, "discountPrice": 1499,
                "fmtPrice": {"originalPrice": "$20.00", "discountPrice": "$14.99"}}}
        })
    }

    #[test]
    fn shelf_item_fields() {
        let i = item_of(&element()).unwrap();
        assert_eq!(i["image"], "w");
        assert_eq!(i["tall"], "tl");
        assert_eq!(i["tags"], "Action, RPG, Indie");
        assert_eq!(i["disc"], 25);
        assert_eq!(i["final"], "$14.99");
        assert_eq!(i["orig"], "$20.00");
        assert_eq!(i["free"], false);
        assert_eq!(i["hasPrice"], true);
        assert_eq!(i["url"], "https://store.epicgames.com/en-US/p/fig");
        assert_eq!(i["pub"], "Seller");
        assert_eq!(i["dev"], "Dev");
        assert_eq!(i["extra"]["items"], "cat1,cat2");
        assert_eq!(i["allTags"].as_array().unwrap().len(), 5);
        assert!(item_of(&json!({"id": "x"})).is_none());
    }

    #[test]
    fn slugs_free_and_promotions() {
        let mut el = element();
        el["catalogNs"] = json!({"mappings": [{"pageType": "productHome", "pageSlug": "fig-game"}]});
        el["price"] = json!({"totalPrice": {"originalPrice": 999, "discountPrice": 0, "fmtPrice": {"originalPrice": "$9.99", "discountPrice": "0"}}});
        let i = item_of(&el).unwrap();
        assert_eq!(i["url"], "https://store.epicgames.com/en-US/p/fig-game");
        assert_eq!(i["free"], true);
        assert_eq!(i["final"], "Free");
        el["productSlug"] = json!("null");
        el["catalogNs"] = json!({});
        assert_eq!(item_of(&el).unwrap()["url"], "https://store.epicgames.com/en-US/browse?q=Fig%20%26%20Co");
        el["promotions"] = json!({"promotionalOffers": [{"promotionalOffers": [{"endDate": "2026-10-20T15:00:00.000Z", "discountSetting": {"discountPercentage": 0}}]}]});
        let p = free_now(&el).unwrap();
        assert_eq!(p["tags"], "Free until 2026-10-20");
        assert_eq!(p["disc"], 100);
        el["promotions"]["promotionalOffers"][0]["promotionalOffers"][0]["discountSetting"]["discountPercentage"] = json!(50);
        assert!(free_now(&el).is_none());
    }

    #[test]
    fn wall_art_takes_every_promotion_once_and_skips_mature() {
        let mut clean = element();
        clean["tags"] = json!([{"name": "Action"}]);
        let mut wide_only = clean.clone();
        wide_only["id"] = json!("offer2");
        wide_only["keyImages"] = json!([{"type": "OfferImageWide", "url": "w2"}]);
        let mut mature = element();
        mature["id"] = json!("offer3");
        mature["keyImages"] = json!([{"type": "DieselStoreFrontTall", "url": "m"}]);
        assert_eq!(wall_art(&[clean.clone(), clean, wide_only, mature]), vec!["tl", "w2"]);
    }

    #[test]
    fn query_and_dates() {
        assert_eq!(country_code("de"), "DE");
        assert_eq!(country_code("419"), "US");
        assert_eq!(civil_from_days(0), (1970, 1, 1));
        assert_eq!(civil_from_days(19_723), (2024, 1, 1));
        let q = shelf_query(Shelf::Deals, "DE", "x");
        assert!(q.contains("onSale: true") && q.contains("sortBy: \"currentPrice\"") && q.contains("country: \"DE\""));
        assert!(shelf_query(Shelf::New, "US", "2026-01-01T00:00:00.000Z").contains("releaseDate: \"[,2026-01-01T00:00:00.000Z]\""));
    }
}
