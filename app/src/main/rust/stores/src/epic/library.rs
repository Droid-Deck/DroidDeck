use serde_json::Value;

use super::net::{self, Request};

const ITEMS_URL: &str =
    "https://library-service.live.use1a.on.epicgames.com/library/api/public/items?includeMetadata=true";
const MAX_PAGES: usize = 2000;
const MARKETPLACE_NAMESPACES: &[&str] = &["ue", "89efe5924d3d467c839449ab6ab52e7f"];

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Owned {
    pub app_name: String,
    pub namespace: String,
    pub catalog_item_id: String,
}

#[derive(Debug, PartialEq, Eq)]
pub enum ListError {
    SignIn,
    Failed(String),
}

pub fn keep_record(record: &Value) -> Option<Owned> {
    let app_name = net::str_of(record, "appName");
    let namespace = net::str_of(record, "namespace");
    if app_name.is_empty() || app_name == "1" || MARKETPLACE_NAMESPACES.contains(&namespace) {
        return None;
    }
    if net::str_of(record, "sandboxType").eq_ignore_ascii_case("PRIVATE") {
        return None;
    }
    if let Some(platforms) = record.get("platform").and_then(|p| p.as_array()) {
        let names: Vec<&str> = platforms.iter().filter_map(|p| p.as_str()).collect();
        if !names.is_empty() && !names.iter().any(|p| *p == "Windows" || *p == "Win32") {
            return None;
        }
    }
    Some(Owned {
        app_name: app_name.to_string(),
        namespace: namespace.to_string(),
        catalog_item_id: net::str_of(record, "catalogItemId").to_string(),
    })
}

pub fn read_page(body: &Value, cursor: Option<&str>) -> (Vec<Owned>, Option<String>) {
    let records = body
        .get("records")
        .and_then(|r| r.as_array())
        .map(|a| a.iter().filter_map(keep_record).collect())
        .unwrap_or_default();
    let next = body
        .get("responseMetadata")
        .map(|m| net::str_of(m, "nextCursor"))
        .filter(|c| !c.is_empty() && Some(*c) != cursor)
        .map(str::to_string);
    (records, next)
}

pub fn list_owned(access_token: &str) -> Result<Vec<Owned>, ListError> {
    let mut out = Vec::new();
    let mut cursor: Option<String> = None;
    for _ in 0..MAX_PAGES {
        let url = match &cursor {
            Some(c) => format!("{ITEMS_URL}&cursor={}", net::percent_encode(c)),
            None => ITEMS_URL.to_string(),
        };
        let reply = net::send(Request::get(&url).bearer(access_token)).map_err(ListError::Failed)?;
        if reply.status == 401 {
            return Err(ListError::SignIn);
        }
        if !reply.ok() {
            return Err(ListError::Failed(format!("Epic library answered HTTP {}", reply.status)));
        }
        let body = reply
            .json()
            .ok_or_else(|| ListError::Failed("Epic library answer could not be read".into()))?;
        let (records, next) = read_page(&body, cursor.as_deref());
        out.extend(records);
        match next {
            Some(n) => cursor = Some(n),
            None => return Ok(out),
        }
    }
    Err(ListError::Failed("Epic library has too many pages".into()))
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    #[test]
    fn record_filtering() {
        let ok = json!({"appName":"Fig","namespace":"ns","catalogItemId":"cat","sandboxType":"PUBLIC","platform":["Windows","Mac"]});
        assert_eq!(keep_record(&ok).unwrap().catalog_item_id, "cat");
        assert!(keep_record(&json!({"appName":"Fig","namespace":"ns","platform":[]})).is_some());
        assert!(keep_record(&json!({"appName":"Fig","namespace":"ns","platform":["Win32"]})).is_some());
        assert!(keep_record(&json!({"appName":"Fig","namespace":"ns","platform":["Mac"]})).is_none());
        assert!(keep_record(&json!({"appName":"Fig","namespace":"ns","sandboxType":"PRIVATE"})).is_none());
        assert!(keep_record(&json!({"appName":"1","namespace":"ns"})).is_none());
        assert!(keep_record(&json!({"appName":"","namespace":"ns"})).is_none());
        assert!(keep_record(&json!({"appName":"Asset","namespace":"ue"})).is_none());
        assert!(keep_record(&json!({"appName":"Asset","namespace":"89efe5924d3d467c839449ab6ab52e7f"})).is_none());
    }

    #[test]
    fn page_cursor_rules() {
        let page = json!({"records":[{"appName":"A","namespace":"n"}],"responseMetadata":{"nextCursor":"c2"}});
        let (records, next) = read_page(&page, Some("c1"));
        assert_eq!(records.len(), 1);
        assert_eq!(next.as_deref(), Some("c2"));
        assert_eq!(read_page(&page, Some("c2")).1, None);
        assert_eq!(read_page(&json!({"records":[]}), None).1, None);
        assert_eq!(read_page(&json!({"responseMetadata":{"nextCursor":""}}), None).1, None);
    }
}
