use serde_json::{json, Value};

use super::net::{self, Body, Request};

pub const CLIENT_ID: &str = "34a02cf8f4414e29b15921876da36f9a";
pub const CLIENT_SECRET: &str = "daafbccc737745039dffe53d94fc76cf";
const ACCOUNT_HOST: &str = "https://account-public-service-prod03.ol.epicgames.com";

pub const REDIRECT_PREFIX: &str = "https://www.epicgames.com/id/api/redirect";

pub const WEBVIEW_USER_AGENT: &str =
    "UELauncher/11.0.1-14907503+++Portal+Release-Live Windows/10.0.19041.1.256.64bit";

const DEFAULT_EXPIRES_IN: i64 = 7200;

pub fn start_url() -> String {
    let redirect = format!("{REDIRECT_PREFIX}?clientId={CLIENT_ID}&responseType=code");
    format!("https://www.epicgames.com/id/login?redirectUrl={}", net::percent_encode(&redirect))
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Tokens {
    pub access_token: String,
    pub refresh_token: Option<String>,
    pub account_id: Option<String>,
    pub display_name: Option<String>,
    pub expires_at_ms: i64,
}

impl Tokens {
    pub fn to_json(&self) -> Value {
        let mut out = json!({ "access_token": self.access_token, "expires_at": self.expires_at_ms });
        if let Some(v) = &self.refresh_token {
            out["refresh_token"] = json!(v);
        }
        if let Some(v) = &self.account_id {
            out["account_id"] = json!(v);
        }
        if let Some(v) = &self.display_name {
            out["display_name"] = json!(v);
        }
        out
    }
}

fn non_empty(v: &Value, key: &str) -> Option<String> {
    Some(net::str_of(v, key).to_string()).filter(|s| !s.is_empty())
}

pub fn parse_token_reply(body: &Value, now_ms: i64) -> Option<Tokens> {
    let access_token = non_empty(body, "access_token")?;
    let from_date = body
        .get("expires_at")
        .and_then(|v| v.as_str())
        .and_then(super::timefmt::utc_prefix_seconds)
        .map(|s| s * 1000);
    let expires_at_ms = from_date.unwrap_or_else(|| {
        let secs = body.get("expires_in").and_then(|v| v.as_i64()).unwrap_or(DEFAULT_EXPIRES_IN);
        now_ms + secs * 1000
    });
    Some(Tokens {
        access_token,
        refresh_token: non_empty(body, "refresh_token"),
        account_id: non_empty(body, "account_id"),
        display_name: non_empty(body, "displayName"),
        expires_at_ms,
    })
}

fn now_ms() -> i64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_millis() as i64)
        .unwrap_or(0)
}

fn token_request(grant: &[(&str, &str)]) -> Result<Tokens, String> {
    let url = format!("{ACCOUNT_HOST}/account/api/oauth/token");
    let mut form: Vec<(String, String)> = grant.iter().map(|(k, v)| (k.to_string(), v.to_string())).collect();
    form.push(("token_type".into(), "eg1".into()));
    let reply = net::send(Request::post(&url, Body::Form(form)).basic(CLIENT_ID, CLIENT_SECRET))?;
    if !reply.ok() {
        return Err(format!("token request answered HTTP {}", reply.status));
    }
    let body = reply.json().ok_or("token answer is not JSON")?;
    parse_token_reply(&body, now_ms()).ok_or_else(|| "token answer has no access token".into())
}

pub fn sign_in_with_code(code: &str) -> Result<Tokens, String> {
    token_request(&[("grant_type", "authorization_code"), ("code", code)])
}

pub fn refresh(refresh_token: &str) -> Result<Tokens, String> {
    token_request(&[("grant_type", "refresh_token"), ("refresh_token", refresh_token)])
}

pub fn exchange_code(access_token: &str) -> Result<String, String> {
    let url = format!("{ACCOUNT_HOST}/account/api/oauth/exchange");
    let reply = net::send(Request::get(&url).bearer(access_token))?;
    if !reply.ok() {
        return Err(format!("exchange answered HTTP {}", reply.status));
    }
    let body = reply.json().ok_or("exchange answer is not JSON")?;
    non_empty(&body, "code").ok_or_else(|| "exchange answer has no code".into())
}

pub fn code_from_page_text(raw: &str) -> Option<String> {
    let mut text = raw.trim();
    if text.len() >= 2 && text.starts_with('"') && text.ends_with('"') {
        text = &text[1..text.len() - 1];
    }
    let text = text.replace("\\n", "").replace("\\r", "").replace("\\\"", "\"");
    let key = "\"authorizationCode\"";
    let mut from = 0;
    while let Some(at) = text[from..].find(key) {
        let rest = text[from + at + key.len()..].trim_start();
        from += at + key.len();
        let Some(rest) = rest.strip_prefix(':') else { continue };
        let Some(rest) = rest.trim_start().strip_prefix('"') else { continue };
        let Some(end) = rest.find('"') else { continue };
        let value = &rest[..end];
        if !value.is_empty() {
            return Some(value.to_string());
        }
    }
    None
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn start_url_wraps_the_redirect() {
        assert_eq!(
            start_url(),
            "https://www.epicgames.com/id/login?redirectUrl=https%3A%2F%2Fwww.epicgames.com%2Fid%2Fapi%2Fredirect%3FclientId%3D34a02cf8f4414e29b15921876da36f9a%26responseType%3Dcode"
        );
    }

    #[test]
    fn code_from_the_redirect_page() {
        let page = r#""{\"warning\":\"x\",\"redirectUrl\":\"https://localhost/launcher/authorized?code=abc\",\"authorizationCode\":\"0123abcd\",\"exchangeCode\":null,\"sid\":null}\n""#;
        assert_eq!(code_from_page_text(page).as_deref(), Some("0123abcd"));
        assert_eq!(code_from_page_text(r#"{"authorizationCode": "zz"}"#).as_deref(), Some("zz"));
        assert_eq!(code_from_page_text(r#"{"authorizationCode":null}"#), None);
        assert_eq!(code_from_page_text(r#"{"authorizationCode":""}"#), None);
        assert_eq!(code_from_page_text("<html>sign in</html>"), None);
    }

    #[test]
    fn token_answers() {
        let t = parse_token_reply(
            &json!({"access_token":"A","refresh_token":"R","account_id":"acc","displayName":"Me","expires_at":"2026-10-13T12:34:56.789Z","expires_in":7200}),
            0,
        )
        .unwrap();
        assert_eq!(t.expires_at_ms, 1_791_894_896_000);
        assert_eq!(t.display_name.as_deref(), Some("Me"));
        let fallback = parse_token_reply(&json!({"access_token":"A","expires_in":60}), 1000).unwrap();
        assert_eq!(fallback.expires_at_ms, 61_000);
        assert_eq!(fallback.refresh_token, None);
        let default = parse_token_reply(&json!({"access_token":"A","expires_at":"soon"}), 0).unwrap();
        assert_eq!(default.expires_at_ms, 7_200_000);
        assert!(parse_token_reply(&json!({"refresh_token":"R"}), 0).is_none());
    }
}
