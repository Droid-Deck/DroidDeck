use std::sync::OnceLock;
use std::time::Duration;

pub const USER_AGENT_PRODUCT: &str = "DroidDeck";

static APP_VERSION: OnceLock<String> = OnceLock::new();

pub fn set_app_version(version: &str) {
    let clean: String = version.trim().chars().filter(|c| !c.is_control()).collect();
    if !clean.is_empty() {
        let _ = APP_VERSION.set(clean);
    }
}

pub fn user_agent() -> String {
    let version = APP_VERSION
        .get()
        .map(String::as_str)
        .unwrap_or(crate::stores_jni::VERSION);
    format!("{USER_AGENT_PRODUCT}/{version}")
}

pub const EPIC_LAUNCHER_USER_AGENT: &str =
    "EpicGamesLauncher/15.17.1-22692490+++Portal+Release-Live Windows/10.0.19041.1.256.64bit";

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Method {
    Get,
    Post,
    Put,
}

pub enum Body {
    Empty,
    Form(Vec<(String, String)>),
    Json(String),
    Bytes(Vec<u8>),
}

pub struct Request<'a> {
    pub method: Method,
    pub url: &'a str,
    pub bearer: Option<&'a str>,
    pub basic: Option<(&'a str, &'a str)>,
    pub body: Body,
    pub read_timeout: Duration,
    pub agent: Option<&'static str>,
}

impl<'a> Request<'a> {
    pub fn get(url: &'a str) -> Self {
        Request {
            method: Method::Get,
            url,
            bearer: None,
            basic: None,
            body: Body::Empty,
            read_timeout: Duration::from_secs(30),
            agent: None,
        }
    }

    pub fn post(url: &'a str, body: Body) -> Self {
        Request {
            method: Method::Post,
            body,
            ..Request::get(url)
        }
    }

    pub fn put(url: &'a str, body: Body) -> Self {
        Request {
            method: Method::Put,
            body,
            ..Request::get(url)
        }
    }

    pub fn agent(mut self, agent: &'static str) -> Self {
        self.agent = Some(agent);
        self
    }

    pub fn bearer(mut self, token: &'a str) -> Self {
        self.bearer = Some(token);
        self
    }

    pub fn basic(mut self, user: &'a str, password: &'a str) -> Self {
        self.basic = Some((user, password));
        self
    }

    pub fn read_timeout(mut self, timeout: Duration) -> Self {
        self.read_timeout = timeout;
        self
    }
}

pub struct Reply {
    pub status: u16,
    pub body: Vec<u8>,
}

impl Reply {
    pub fn ok(&self) -> bool {
        (200..300).contains(&self.status)
    }

    pub fn text(&self) -> String {
        String::from_utf8_lossy(&self.body).into_owned()
    }

    pub fn json(&self) -> Option<serde_json::Value> {
        if self.body.iter().all(|b| b.is_ascii_whitespace()) {
            return Some(serde_json::Value::Object(Default::default()));
        }
        serde_json::from_slice(&self.body).ok()
    }
}

const CONNECT_TIMEOUT: Duration = Duration::from_secs(30);
const WHOLE_REQUEST_CAP: Duration = Duration::from_secs(600);

pub fn send(request: Request<'_>) -> Result<Reply, String> {
    let runtime = tokio::runtime::Builder::new_current_thread()
        .enable_all()
        .build()
        .map_err(|e| format!("runtime: {e}"))?;
    runtime.block_on(send_async(request))
}

async fn send_async(request: Request<'_>) -> Result<Reply, String> {
    use reqwest::header::{ACCEPT, AUTHORIZATION, CONTENT_TYPE};
    let client = reqwest::Client::builder()
        .user_agent(request.agent.map(str::to_string).unwrap_or_else(user_agent))
        .connect_timeout(CONNECT_TIMEOUT)
        .read_timeout(request.read_timeout)
        .timeout(WHOLE_REQUEST_CAP)
        .build()
        .map_err(|e| format!("http client: {}", e.without_url()))?;
    let mut builder = match request.method {
        Method::Get => client.get(request.url),
        Method::Post => client.post(request.url),
        Method::Put => client.put(request.url),
    };
    if let Some(token) = request.bearer {
        builder = builder.header(AUTHORIZATION, format!("Bearer {token}"));
    }
    if let Some((user, password)) = request.basic {
        let pair = format!("{user}:{password}");
        builder = builder.header(AUTHORIZATION, format!("Basic {}", base64(pair.as_bytes())));
    }
    builder = match request.body {
        Body::Empty => builder,
        Body::Form(pairs) => builder
            .header(CONTENT_TYPE, "application/x-www-form-urlencoded")
            .header(ACCEPT, "application/json")
            .body(form_encode(&pairs)),
        Body::Json(text) => builder
            .header(CONTENT_TYPE, "application/json")
            .header(ACCEPT, "application/json")
            .body(text),
        Body::Bytes(bytes) => builder
            .header(CONTENT_TYPE, "application/octet-stream")
            .body(bytes),
    };
    let response = builder.send().await.map_err(describe)?;
    let status = response.status().as_u16();
    let body = response.bytes().await.map_err(describe)?.to_vec();
    Ok(Reply { status, body })
}

fn describe(err: reqwest::Error) -> String {
    let kind = if err.is_timeout() {
        "timed out"
    } else if err.is_connect() {
        "could not connect"
    } else if err.is_body() || err.is_decode() {
        "body could not be read"
    } else {
        "request failed"
    };
    format!("{kind}: {}", err.without_url())
}

pub fn percent_encode(text: &str) -> String {
    let mut out = String::with_capacity(text.len());
    for b in text.bytes() {
        if b.is_ascii_alphanumeric() || matches!(b, b'-' | b'_' | b'.' | b'~') {
            out.push(b as char);
        } else {
            out.push('%');
            out.push(HEX_UPPER[(b >> 4) as usize] as char);
            out.push(HEX_UPPER[(b & 15) as usize] as char);
        }
    }
    out
}

const HEX_UPPER: &[u8; 16] = b"0123456789ABCDEF";

pub fn form_encode(pairs: &[(String, String)]) -> String {
    pairs
        .iter()
        .map(|(k, v)| format!("{}={}", percent_encode(k), percent_encode(v)))
        .collect::<Vec<_>>()
        .join("&")
}

pub fn base64(data: &[u8]) -> String {
    const TABLE: &[u8; 64] = b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    let mut out = String::with_capacity(data.len().div_ceil(3) * 4);
    for group in data.chunks(3) {
        let b0 = group[0] as u32;
        let b1 = group.get(1).copied().unwrap_or(0) as u32;
        let b2 = group.get(2).copied().unwrap_or(0) as u32;
        let n = (b0 << 16) | (b1 << 8) | b2;
        out.push(TABLE[(n >> 18) as usize & 63] as char);
        out.push(TABLE[(n >> 12) as usize & 63] as char);
        out.push(if group.len() > 1 { TABLE[(n >> 6) as usize & 63] as char } else { '=' });
        out.push(if group.len() > 2 { TABLE[n as usize & 63] as char } else { '=' });
    }
    out
}

pub fn origin(url: &str) -> String {
    let after_scheme = url.find("://").map(|i| i + 3).unwrap_or(0);
    let end = url[after_scheme..]
        .find(['/', '?', '#'])
        .map(|i| after_scheme + i)
        .unwrap_or(url.len());
    url[..end].to_string()
}

pub fn str_of<'v>(value: &'v serde_json::Value, key: &str) -> &'v str {
    value.get(key).and_then(|v| v.as_str()).unwrap_or("")
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn percent_encoding_keeps_only_unreserved() {
        assert_eq!(percent_encode("abc-_.~XYZ09"), "abc-_.~XYZ09");
        assert_eq!(percent_encode("a b+c/d=e&f"), "a%20b%2Bc%2Fd%3De%26f");
        assert_eq!(percent_encode("é"), "%C3%A9");
    }

    #[test]
    fn form_values_are_encoded() {
        let body = form_encode(&[
            ("grant_type".into(), "refresh_token".into()),
            ("refresh_token".into(), "a+b/c=".into()),
        ]);
        assert_eq!(body, "grant_type=refresh_token&refresh_token=a%2Bb%2Fc%3D");
    }

    #[test]
    fn base64_matches_rfc4648_vectors() {
        assert_eq!(base64(b""), "");
        assert_eq!(base64(b"f"), "Zg==");
        assert_eq!(base64(b"fo"), "Zm8=");
        assert_eq!(base64(b"foo"), "Zm9v");
        assert_eq!(base64(b"foobar"), "Zm9vYmFy");
    }

    #[test]
    fn origin_is_scheme_and_host() {
        assert_eq!(origin("https://cdn.example.com/Builds/x?y=1"), "https://cdn.example.com");
        assert_eq!(origin("https://cdn.example.com"), "https://cdn.example.com");
    }

    #[test]
    fn user_agent_names_droiddeck() {
        assert!(user_agent().starts_with("DroidDeck/"));
    }
}
