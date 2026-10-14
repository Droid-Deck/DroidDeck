use std::time::Duration;

use serde_json::{json, Value};

use super::net::{self, Body, Request};

const STORAGE_HOST: &str = "https://datastorage-public-service-liveegs.live.use1a.on.epicgames.com";

fn base(account_id: &str, app_name: &str) -> String {
    format!(
        "{STORAGE_HOST}/api/v1/access/egstore/savesync/{}/{}/",
        net::percent_encode(account_id),
        net::percent_encode(app_name)
    )
}

pub fn name_of(key: &str, app_name: &str) -> String {
    let marker = format!("/{app_name}/");
    if let Some(at) = key.find(&marker) {
        return key[at + marker.len()..].to_string();
    }
    let lead = format!("{app_name}/");
    key.strip_prefix(&lead).unwrap_or(key).to_string()
}

pub fn read_listing(answer: &Value, app_name: &str) -> Vec<Value> {
    let Some(files) = answer.get("files").and_then(|f| f.as_object()) else {
        return Vec::new();
    };
    files
        .iter()
        .map(|(key, f)| {
            let modified = super::timefmt::iso_seconds(net::str_of(f, "lastModified"))
                .map(|s| s * 1000)
                .unwrap_or(-1);
            json!({
                "name": name_of(key, app_name),
                "modified": modified,
                "hash": net::str_of(f, "hash"),
                "readLink": net::str_of(f, "readLink"),
            })
        })
        .filter(|f| !net::str_of(f, "name").is_empty())
        .collect()
}

pub fn list(access_token: &str, account_id: &str, app_name: &str) -> Result<Vec<Value>, String> {
    let url = base(account_id, app_name);
    let reply = net::send(Request::get(&url).bearer(access_token))?;
    if reply.status == 404 {
        return Ok(Vec::new());
    }
    if !reply.ok() {
        return Err(format!("Epic cloud list HTTP {}", reply.status));
    }
    let answer = reply.json().ok_or("Epic cloud list could not be read")?;
    Ok(read_listing(&answer, app_name))
}

pub fn write_links(access_token: &str, account_id: &str, app_name: &str, names: &[String]) -> Result<Value, String> {
    let url = base(account_id, app_name);
    let body = json!({ "files": names }).to_string();
    let reply = net::send(Request::post(&url, Body::Json(body)).bearer(access_token))?;
    if !reply.ok() {
        return Err(format!("Epic cloud upload request HTTP {}", reply.status));
    }
    let answer = reply.json().ok_or("Epic cloud upload answer could not be read")?;
    let mut links = serde_json::Map::new();
    if let Some(files) = answer.get("files").and_then(|f| f.as_object()) {
        for (key, f) in files {
            let link = net::str_of(f, "writeLink");
            if !link.is_empty() {
                links.insert(name_of(key, app_name), json!(link));
            }
        }
    }
    Ok(Value::Object(links))
}

pub fn read(link: &str) -> Result<Vec<u8>, String> {
    let reply = net::send(Request::get(link).read_timeout(Duration::from_secs(30)))?;
    if !reply.ok() {
        return Err(format!("Epic cloud download HTTP {}", reply.status));
    }
    Ok(reply.body)
}

pub fn write(link: &str, data: Vec<u8>) -> Result<(), String> {
    let reply = net::send(Request::put(link, Body::Bytes(data)))?;
    if !reply.ok() {
        return Err(format!("Epic cloud upload HTTP {}", reply.status));
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn names_from_keys() {
        assert_eq!(name_of("acc/Fig/Saves/slot1.sav", "Fig"), "Saves/slot1.sav");
        assert_eq!(name_of("Fig/slot1.sav", "Fig"), "slot1.sav");
        assert_eq!(name_of("slot1.sav", "Fig"), "slot1.sav");
    }

    #[test]
    fn listing() {
        let answer = json!({"files": {
            "acc/Fig/a.sav": {"lastModified": "2024-05-01T10:00:00.000Z", "hash": "abc", "readLink": "https://r/a"},
            "acc/Fig/": {"lastModified": "", "hash": "", "readLink": ""}
        }});
        let files = read_listing(&answer, "Fig");
        assert_eq!(files.len(), 1);
        assert_eq!(files[0]["name"], "a.sav");
        assert_eq!(files[0]["modified"], 1_714_557_600_000i64);
        assert!(read_listing(&json!({}), "Fig").is_empty());
    }
}
