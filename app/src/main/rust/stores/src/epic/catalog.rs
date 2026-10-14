use serde_json::{json, Value};

use super::net::{self, Request};

const CATALOG_HOST: &str = "https://catalog-public-service-prod06.ol.epicgames.com";

fn items_url(namespace: &str, item_id: &str, with_relations: bool) -> String {
    let mut url = format!(
        "{CATALOG_HOST}/catalog/api/shared/namespace/{}/bulk/items?id={}",
        net::percent_encode(namespace),
        net::percent_encode(item_id)
    );
    if with_relations {
        url.push_str("&includeDLCDetails=true&includeMainGameDetails=true");
    }
    url.push_str("&country=US");
    url
}

pub fn pick_entry<'v>(answer: &'v Value, item_id: &str) -> Option<&'v Value> {
    let map = answer.as_object()?;
    map.get(item_id).or_else(|| map.values().next())
}

fn fetch(access_token: &str, namespace: &str, item_id: &str, relations: bool) -> Result<Option<Value>, ()> {
    let url = items_url(namespace, item_id, relations);
    let reply = net::send(Request::get(&url).bearer(access_token)).map_err(|_| ())?;
    if !reply.ok() {
        return Err(());
    }
    let answer = reply.json().ok_or(())?;
    Ok(pick_entry(&answer, item_id).cloned())
}

fn image(entry: &Value, types: &[&str]) -> String {
    let Some(list) = entry.get("keyImages").and_then(|v| v.as_array()) else {
        return String::new();
    };
    list.iter()
        .find(|img| types.contains(&net::str_of(img, "type")) && !net::str_of(img, "url").is_empty())
        .map(|img| net::str_of(img, "url").to_string())
        .unwrap_or_default()
}

fn attribute_of(entry: &Value, name: &str) -> Option<String> {
    entry
        .get("customAttributes")
        .and_then(|a| a.get(name))
        .and_then(|a| a.get("value"))
        .and_then(|v| v.as_str())
        .map(str::to_string)
}

pub fn details_of(entry: &Value) -> Value {
    let publisher = attribute_of(entry, "publisherName")
        .map(|p| p.trim().to_string())
        .filter(|p| !p.is_empty())
        .unwrap_or_else(|| {
            entry.get("seller").map(|s| net::str_of(s, "name").trim().to_string()).unwrap_or_default()
        });
    let release = Some(net::str_of(entry, "viewableDate"))
        .filter(|s| !s.is_empty())
        .unwrap_or_else(|| net::str_of(entry, "effectiveDate"));
    let offline = attribute_of(entry, "CanRunOffline").map(|v| !v.eq_ignore_ascii_case("false")).unwrap_or(true);
    let main_game = entry.get("mainGameItem");
    json!({
        "title": net::str_of(entry, "title"),
        "developer": net::str_of(entry, "developer"),
        "description": net::str_of(entry, "description"),
        "publisher": publisher,
        "releaseDate": release,
        "tall": image(entry, &["DieselGameBoxTall"]),
        "wide": image(entry, &["DieselGameBox", "Thumbnail"]),
        "canRunOffline": offline,
        "dlc": main_game.is_some_and(|m| !m.is_null()),
        "mainGameId": main_game.map(|m| net::str_of(m, "id")).unwrap_or(""),
    })
}

pub fn details(access_token: &str, namespace: &str, item_id: &str) -> Option<Value> {
    fetch(access_token, namespace, item_id, true).ok().flatten().map(|e| details_of(&e))
}

pub fn attribute(access_token: &str, namespace: &str, item_id: &str, name: &str) -> Option<String> {
    match fetch(access_token, namespace, item_id, false) {
        Err(()) => None,
        Ok(None) => Some(String::new()),
        Ok(Some(entry)) => Some(attribute_of(&entry, name).unwrap_or_default()),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn details_from_an_entry() {
        let answer = json!({
            "other": {"title": "Wrong"},
            "cat1": {
                "title": "Game", "developer": "Dev", "description": "Text",
                "keyImages": [
                    {"type": "Thumbnail", "url": "thumb"},
                    {"type": "DieselGameBox", "url": ""},
                    {"type": "DieselGameBoxTall", "url": "tall"}
                ],
                "customAttributes": {"CanRunOffline": {"value": "FALSE"}, "publisherName": {"value": " Pub "}},
                "seller": {"name": "Seller"},
                "effectiveDate": "2020-01-01T00:00:00.000Z"
            }
        });
        let d = details_of(pick_entry(&answer, "cat1").unwrap());
        assert_eq!(d["title"], "Game");
        assert_eq!(d["tall"], "tall");
        assert_eq!(d["wide"], "thumb");
        assert_eq!(d["publisher"], "Pub");
        assert_eq!(d["canRunOffline"], false);
        assert_eq!(d["releaseDate"], "2020-01-01T00:00:00.000Z");
        assert_eq!(d["dlc"], false);
        let dlc = details_of(&json!({"mainGameItem": {"id": "base"}, "seller": {"name": "S"}, "viewableDate": "v"}));
        assert_eq!(dlc["dlc"], true);
        assert_eq!(dlc["mainGameId"], "base");
        assert_eq!(dlc["publisher"], "S");
        assert_eq!(dlc["releaseDate"], "v");
        assert_eq!(dlc["canRunOffline"], true);
        assert_eq!(pick_entry(&json!({"x": {"title": "Only"}}), "y").unwrap()["title"], "Only");
    }
}
