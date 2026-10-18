use std::sync::OnceLock;
use std::time::Duration;

pub const USER_AGENT_PRODUCT: &str = "DroidDeck";

pub const BROWSER_USER_AGENT: &str =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:128.0) Gecko/20100101 Firefox/128.0";

pub const LAUNCHER_USER_AGENT: &str = "AGSLauncher/1.0.0";

pub const LAUNCHER_PACKAGE: (&str, &str) = ("UserAgent", "com.amazon.agslauncher.win/3.0.9202.1");

static APP_VERSION: OnceLock<String> = OnceLock::new();

pub fn set_app_version(version: &str) {
    let clean: String = version.trim().chars().filter(|c| !c.is_control()).collect();
    if !clean.is_empty() {
        let _ = APP_VERSION.set(clean);
    }
}

pub fn user_agent() -> String {
    let version = APP_VERSION.get().map(String::as_str).unwrap_or(crate::stores_jni::VERSION);
    format!("{USER_AGENT_PRODUCT}/{version}")
}

pub const CONNECT_TIMEOUT: Duration = Duration::from_secs(30);
pub const JSON_READ_TIMEOUT: Duration = Duration::from_secs(30);
pub const BYTES_READ_TIMEOUT: Duration = Duration::from_secs(120);
const WHOLE_REQUEST_CAP: Duration = Duration::from_secs(600);
const MAX_BODY: usize = 256 * 1024 * 1024;

pub struct Call {
    pub post: bool,
    pub url: String,
    pub user_agent: String,
    pub headers: Vec<(String, String)>,
    pub json_body: Option<String>,
    pub read_timeout: Duration,
}

impl Call {
    pub fn get(url: &str, user_agent: String) -> Call {
        Call {
            post: false,
            url: url.to_string(),
            user_agent,
            headers: Vec::new(),
            json_body: None,
            read_timeout: JSON_READ_TIMEOUT,
        }
    }

    pub fn post_json(url: &str, user_agent: String, body: &serde_json::Value) -> Call {
        Call { post: true, json_body: Some(body.to_string()), ..Call::get(url, user_agent) }
    }

    pub fn header(mut self, name: &str, value: &str) -> Call {
        self.headers.push((name.to_string(), value.to_string()));
        self
    }

    pub fn read_timeout(mut self, timeout: Duration) -> Call {
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

    pub fn json(&self) -> Option<serde_json::Value> {
        serde_json::from_slice(&self.body).ok()
    }

    pub fn snippet(&self) -> String {
        let text = String::from_utf8_lossy(&self.body[..self.body.len().min(200)]).into_owned();
        text.chars().map(|c| if c.is_control() { ' ' } else { c }).collect()
    }
}

pub fn send(call: Call) -> Result<Reply, String> {
    let runtime = tokio::runtime::Builder::new_current_thread()
        .enable_all()
        .build()
        .map_err(|e| format!("runtime: {e}"))?;
    runtime.block_on(send_async(call))
}

async fn send_async(call: Call) -> Result<Reply, String> {
    use reqwest::header::{ACCEPT, CONTENT_TYPE};
    let client = reqwest::Client::builder()
        .user_agent(call.user_agent.as_str())
        .connect_timeout(CONNECT_TIMEOUT)
        .read_timeout(call.read_timeout)
        .timeout(WHOLE_REQUEST_CAP)
        .build()
        .map_err(|e| format!("http client: {}", e.without_url()))?;
    let mut builder = if call.post { client.post(call.url.as_str()) } else { client.get(call.url.as_str()) };
    for (name, value) in &call.headers {
        builder = builder.header(name.as_str(), value.as_str());
    }
    if let Some(body) = call.json_body {
        builder = builder.header(CONTENT_TYPE, "application/json").header(ACCEPT, "application/json").body(body);
    }
    let mut response = builder.send().await.map_err(describe)?;
    let status = response.status().as_u16();
    let mut body = Vec::new();
    while let Some(piece) = response.chunk().await.map_err(describe)? {
        if body.len() + piece.len() > MAX_BODY {
            return Err("answer is too large".into());
        }
        body.extend_from_slice(&piece);
    }
    Ok(Reply { status, body })
}

fn describe(err: reqwest::Error) -> String {
    let kind = if err.is_timeout() {
        "timed out"
    } else if err.is_connect() {
        "could not connect"
    } else if err.is_body() || err.is_decode() {
        "answer could not be read"
    } else {
        "request failed"
    };
    format!("{kind}: {}", err.without_url())
}

const HEX_LOWER: &[u8; 16] = b"0123456789abcdef";
const HEX_UPPER: &[u8; 16] = b"0123456789ABCDEF";

pub fn hex_lower(bytes: &[u8]) -> String {
    bytes.iter().flat_map(|b| [HEX_LOWER[(b >> 4) as usize] as char, HEX_LOWER[(b & 15) as usize] as char]).collect()
}

pub fn hex_upper(bytes: &[u8]) -> String {
    bytes.iter().flat_map(|b| [HEX_UPPER[(b >> 4) as usize] as char, HEX_UPPER[(b & 15) as usize] as char]).collect()
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

pub fn percent_decode(text: &str) -> String {
    fn nibble(b: u8) -> Option<u8> {
        match b {
            b'0'..=b'9' => Some(b - b'0'),
            b'a'..=b'f' => Some(b - b'a' + 10),
            b'A'..=b'F' => Some(b - b'A' + 10),
            _ => None,
        }
    }
    let bytes = text.as_bytes();
    let mut out = Vec::with_capacity(bytes.len());
    let mut i = 0;
    while i < bytes.len() {
        if bytes[i] == b'%' && i + 2 < bytes.len() {
            if let (Some(h), Some(l)) = (nibble(bytes[i + 1]), nibble(bytes[i + 2])) {
                out.push(h << 4 | l);
                i += 3;
                continue;
            }
        }
        out.push(bytes[i]);
        i += 1;
    }
    String::from_utf8_lossy(&out).into_owned()
}

pub fn base64url(data: &[u8]) -> String {
    const TABLE: &[u8; 64] = b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
    let mut out = String::with_capacity(data.len().div_ceil(3) * 4);
    for group in data.chunks(3) {
        let n = (group[0] as u32) << 16 | (*group.get(1).unwrap_or(&0) as u32) << 8 | *group.get(2).unwrap_or(&0) as u32;
        out.push(TABLE[(n >> 18) as usize & 63] as char);
        out.push(TABLE[(n >> 12) as usize & 63] as char);
        if group.len() > 1 {
            out.push(TABLE[(n >> 6) as usize & 63] as char);
        }
        if group.len() > 2 {
            out.push(TABLE[n as usize & 63] as char);
        }
    }
    out
}

pub fn scrub_urls(line: &str) -> String {
    let mut out = String::with_capacity(line.len());
    let mut rest = line;
    loop {
        let at = [rest.find("https://"), rest.find("http://")].into_iter().flatten().min();
        let Some(at) = at else {
            out.push_str(rest);
            return out;
        };
        out.push_str(&rest[..at]);
        let tail = &rest[at..];
        let end = tail.find(|c: char| c.is_whitespace() || matches!(c, ')' | '"' | '\'' | '>' | ',')).unwrap_or(tail.len());
        let url = &tail[..end];
        let cut = url.find(['?', '#']).unwrap_or(url.len());
        out.push_str(&url[..cut]);
        rest = &tail[end..];
    }
}

pub fn str_of<'v>(value: &'v serde_json::Value, key: &str) -> &'v str {
    value.get(key).and_then(|v| v.as_str()).unwrap_or("")
}

pub fn str_at<'v>(value: &'v serde_json::Value, path: &[&str]) -> &'v str {
    let mut v = value;
    for key in path {
        match v.get(key) {
            Some(next) => v = next,
            None => return "",
        }
    }
    v.as_str().unwrap_or("")
}

pub fn now_ms() -> i64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_millis() as i64)
        .unwrap_or(0)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn hex_both_cases() {
        assert_eq!(hex_lower(&[0x00, 0xab, 0x7f]), "00ab7f");
        assert_eq!(hex_upper(&[0x00, 0xab, 0x7f]), "00AB7F");
    }

    #[test]
    fn percent_round_trip() {
        assert_eq!(percent_encode("device:ab/c d"), "device%3Aab%2Fc%20d");
        assert_eq!(percent_decode("a%3Ab%2fc+d"), "a:b/c+d");
        assert_eq!(percent_decode("bad%zzend%4"), "bad%zzend%4");
        assert_eq!(percent_decode("%41"), "A");
    }

    #[test]
    fn base64url_vectors() {
        assert_eq!(base64url(b""), "");
        assert_eq!(base64url(b"f"), "Zg");
        assert_eq!(base64url(b"fo"), "Zm8");
        assert_eq!(base64url(b"foo"), "Zm9v");
        assert_eq!(base64url(&[0xfb, 0xff]), "-_8");
    }

    #[test]
    fn urls_lose_their_query_in_logs() {
        assert_eq!(
            scrub_urls("GET https://cdn.example/x/files/ab?sig=SECRET&e=1 failed (https://a.b/c#frag)"),
            "GET https://cdn.example/x/files/ab failed (https://a.b/c)"
        );
        assert_eq!(scrub_urls("no url here"), "no url here");
    }

    #[test]
    fn nested_strings() {
        let v = serde_json::json!({"a":{"b":{"c":"x"}},"n":1});
        assert_eq!(str_at(&v, &["a", "b", "c"]), "x");
        assert_eq!(str_at(&v, &["a", "z"]), "");
        assert_eq!(str_of(&v, "n"), "");
    }

    #[test]
    fn product_user_agent() {
        assert!(user_agent().starts_with("DroidDeck/"));
    }
}
