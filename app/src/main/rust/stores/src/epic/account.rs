use serde_json::{json, Value};

use super::net::{self, Body, Request};

pub const CLIENT_ID: &str = "34a02cf8f4414e29b15921876da36f9a";
pub const CLIENT_SECRET: &str = "daafbccc737745039dffe53d94fc76cf";
const ACCOUNT_HOST: &str = "https://account-public-service-prod03.ol.epicgames.com";

// Epic's login page diverts the launcher client to a welcome page that needs the desktop
// launcher's JS bridge, so a browser sign-in goes through this client's device grant and an
// exchange code instead.
const DEVICE_CLIENT_ID: &str = "98f7e42c2e3a4f86a74eb43fbb41ed39";
const DEVICE_CLIENT_SECRET: &str = "0a2449a2-001a-451e-afec-3e812901c4d7";

pub const WEBVIEW_USER_AGENT: &str =
    "UELauncher/11.0.1-14907503+++Portal+Release-Live Windows/10.0.19041.1.256.64bit";

const DEFAULT_EXPIRES_IN: i64 = 7200;

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct DeviceStart {
    pub url: String,
    pub user_code: String,
    pub device_code: String,
    pub interval_s: i64,
    pub expires_in_s: i64,
}

pub fn parse_device_start(body: &Value) -> Option<DeviceStart> {
    Some(DeviceStart {
        url: non_empty(body, "verification_uri_complete")?,
        user_code: non_empty(body, "user_code")?,
        device_code: non_empty(body, "device_code")?,
        interval_s: body.get("interval").and_then(Value::as_i64).unwrap_or(10).max(1),
        expires_in_s: body.get("expires_in").and_then(Value::as_i64).unwrap_or(600),
    })
}

pub fn device_start() -> Result<DeviceStart, String> {
    let url = format!("{ACCOUNT_HOST}/account/api/oauth/token");
    let grant = vec![("grant_type".to_string(), "client_credentials".to_string())];
    let reply = net::send(Request::post(&url, Body::Form(grant)).basic(DEVICE_CLIENT_ID, DEVICE_CLIENT_SECRET))?;
    if !reply.ok() {
        return Err(format!("client token answered HTTP {}", reply.status));
    }
    let body = reply.json().ok_or("client token answer is not JSON")?;
    let client_token = non_empty(&body, "access_token").ok_or("client token answer has no token")?;
    let url = format!("{ACCOUNT_HOST}/account/api/oauth/deviceAuthorization");
    let reply = net::send(Request::post(&url, Body::Form(Vec::new())).bearer(&client_token))?;
    if !reply.ok() {
        return Err(format!("device authorization answered HTTP {}", reply.status));
    }
    parse_device_start(&reply.json().ok_or("device authorization answer is not JSON")?)
        .ok_or_else(|| "device authorization answer is incomplete".into())
}

#[derive(Debug, PartialEq, Eq)]
pub enum DevicePoll {
    Pending,
    Done(Tokens),
    Failed(String),
}

fn pending(body: &Value) -> bool {
    matches!(
        net::str_of(body, "errorCode"),
        "errors.com.epicgames.account.oauth.authorization_pending" | "errors.com.epicgames.common.slow_down"
    ) || matches!(net::str_of(body, "error"), "authorization_pending" | "slow_down")
}

pub fn device_poll(device_code: &str) -> DevicePoll {
    let url = format!("{ACCOUNT_HOST}/account/api/oauth/token");
    let grant = vec![
        ("grant_type".to_string(), "device_code".to_string()),
        ("device_code".to_string(), device_code.to_string()),
    ];
    let reply = match net::send(Request::post(&url, Body::Form(grant)).basic(DEVICE_CLIENT_ID, DEVICE_CLIENT_SECRET)) {
        Ok(r) => r,
        Err(_) => return DevicePoll::Pending,
    };
    let body = reply.json().unwrap_or(Value::Null);
    if !reply.ok() {
        if pending(&body) || reply.status >= 500 {
            return DevicePoll::Pending;
        }
        let code = net::str_of(&body, "errorCode");
        return DevicePoll::Failed(if code.is_empty() { format!("device grant answered HTTP {}", reply.status) } else { code.to_string() });
    }
    let Some(device_token) = non_empty(&body, "access_token") else {
        return DevicePoll::Failed("device grant answer has no access token".into());
    };
    let result = exchange_code(&device_token).and_then(|code| token_request(&[("grant_type", "exchange_code"), ("exchange_code", &code)]));
    let kill = format!("{ACCOUNT_HOST}/account/api/oauth/sessions/kill/{device_token}");
    let _ = net::send(Request::delete(&kill).bearer(&device_token));
    match result {
        Ok(tokens) => DevicePoll::Done(tokens),
        Err(e) => DevicePoll::Failed(e),
    }
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

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn device_start_reads_epic_answer() {
        let body = serde_json::json!({
            "user_code": "LQWFTNKF", "device_code": "U1BC", "verification_uri": "https://www.epicgames.com/activate",
            "verification_uri_complete": "https://www.epicgames.com/activate?userCode=LQWFTNKF",
            "expires_in": 600, "interval": 10,
        });
        assert_eq!(
            parse_device_start(&body),
            Some(DeviceStart {
                url: "https://www.epicgames.com/activate?userCode=LQWFTNKF".into(),
                user_code: "LQWFTNKF".into(),
                device_code: "U1BC".into(),
                interval_s: 10,
                expires_in_s: 600,
            })
        );
        assert_eq!(parse_device_start(&serde_json::json!({ "user_code": "A", "device_code": "B" })), None);
    }

    #[test]
    fn device_grant_waits_while_the_user_has_not_confirmed() {
        assert!(pending(&serde_json::json!({ "errorCode": "errors.com.epicgames.account.oauth.authorization_pending" })));
        assert!(pending(&serde_json::json!({ "error": "slow_down" })));
        assert!(!pending(&serde_json::json!({ "errorCode": "errors.com.epicgames.account.oauth.authorization_code_not_found" })));
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
