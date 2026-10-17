use std::io::Read;

use serde_json::{json, Value};
use sha2::{Digest, Sha256};

use super::net::{self, Call};

pub const DEVICE_TYPE: &str = "A2UMVHOX7UP4V7";
pub const APP_NAME: &str = "AGSLauncher for Windows";
pub const APP_VERSION: &str = "1.0.0";
pub const OS_VERSION: &str = "10.0.19044.0";
pub const DEVICE_MODEL: &str = "Windows";
pub const CLIENT_DOMAIN: &str = "DeviceLegacy";
pub const REGISTRATION_DOMAIN: &str = "Device";
pub const ASSOCIATION_HANDLE: &str = "amzn_sonic_games_launcher";
pub const MARKETPLACE_US: &str = "ATVPDKIKX0DER";

pub const SIGN_IN_PAGE: &str = "https://www.amazon.com/ap/signin";
pub const RETURN_TO: &str = "https://www.amazon.com";
pub const CODE_PARAMETER: &str = "openid.oa2.authorization_code";
pub const REDIRECT_MARKER: &str = "openid.oa2.authorization_code=";

const REGISTER_URL: &str = "https://api.amazon.com/auth/register";
const TOKEN_URL: &str = "https://api.amazon.com/auth/token";
const DEREGISTER_URL: &str = "https://api.amazon.com/auth/deregister";
const DEFAULT_EXPIRES_IN: i64 = 3600;

fn random_bytes(n: usize) -> Result<Vec<u8>, String> {
    let mut out = vec![0u8; n];
    std::fs::File::open("/dev/urandom")
        .and_then(|mut f| f.read_exact(&mut out))
        .map_err(|e| format!("no randomness: {e}"))?;
    Ok(out)
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Attempt {
    pub serial: String,
    pub client_id: String,
    pub verifier: String,
}

pub fn serial_from(random: &[u8; 16]) -> String {
    let mut b = *random;
    b[6] = (b[6] & 0x0f) | 0x40;
    b[8] = (b[8] & 0x3f) | 0x80;
    net::hex_upper(&b)
}

pub fn client_id_for(serial: &str) -> String {
    net::hex_lower(format!("{serial}#{DEVICE_TYPE}").as_bytes())
}

pub fn challenge_for(verifier: &str) -> String {
    net::base64url(&Sha256::digest(verifier.as_bytes()))
}

impl Attempt {
    pub fn new() -> Result<Attempt, String> {
        let random = random_bytes(48)?;
        let uuid: [u8; 16] = random[..16].try_into().expect("16 bytes");
        let serial = serial_from(&uuid);
        Ok(Attempt { client_id: client_id_for(&serial), serial, verifier: net::base64url(&random[16..48]) })
    }

    pub fn start_url(&self) -> String {
        let client = format!("device:{}", self.client_id);
        let challenge = challenge_for(&self.verifier);
        let params: [(&str, &str); 17] = [
            ("openid.ns", "http://specs.openid.net/auth/2.0"),
            ("openid.claimed_id", "http://specs.openid.net/auth/2.0/identifier_select"),
            ("openid.identity", "http://specs.openid.net/auth/2.0/identifier_select"),
            ("openid.mode", "checkid_setup"),
            ("openid.oa2.scope", "device_auth_access"),
            ("openid.ns.oa2", "http://www.amazon.com/ap/ext/oauth/2"),
            ("openid.oa2.response_type", "code"),
            ("openid.oa2.code_challenge_method", "S256"),
            ("openid.oa2.client_id", client.as_str()),
            ("language", "en_US"),
            ("marketPlaceId", MARKETPLACE_US),
            ("openid.return_to", RETURN_TO),
            ("openid.pape.max_auth_age", "0"),
            ("openid.ns.pape", "http://specs.openid.net/extensions/pape/1.0"),
            ("openid.assoc_handle", ASSOCIATION_HANDLE),
            ("pageId", ASSOCIATION_HANDLE),
            ("openid.oa2.code_challenge", challenge.as_str()),
        ];
        let query: Vec<String> = params.iter().map(|(k, v)| format!("{k}={}", net::percent_encode(v))).collect();
        format!("{SIGN_IN_PAGE}?{}", query.join("&"))
    }
}

pub fn code_from_url(url: &str) -> Option<String> {
    let query = &url[url.find('?')? + 1..];
    let query = query.split('#').next().unwrap_or("");
    query
        .split('&')
        .filter_map(|pair| pair.split_once('='))
        .find(|(k, _)| *k == CODE_PARAMETER)
        .map(|(_, v)| net::percent_decode(v))
        .filter(|v| !v.is_empty())
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Registered {
    pub access_token: String,
    pub refresh_token: String,
    pub expires_at_ms: i64,
    pub device_serial: String,
    pub client_id: String,
    pub display_name: String,
}

impl Registered {
    pub fn to_json(&self) -> Value {
        let mut out = json!({
            "access_token": self.access_token,
            "refresh_token": self.refresh_token,
            "device_serial": self.device_serial,
            "client_id": self.client_id,
            "expires_at": self.expires_at_ms,
        });
        if !self.display_name.is_empty() {
            out["display_name"] = json!(self.display_name);
        }
        out
    }
}

fn expires_in(v: Option<&Value>) -> i64 {
    let secs = v.and_then(|v| v.as_i64().or_else(|| v.as_str().and_then(|s| s.trim().parse().ok())));
    secs.filter(|s| *s > 0).unwrap_or(DEFAULT_EXPIRES_IN)
}

pub fn registration_body(code: &str, attempt: &Attempt) -> Value {
    json!({
        "auth_data": {
            "authorization_code": code,
            "client_domain": CLIENT_DOMAIN,
            "client_id": attempt.client_id,
            "code_algorithm": "SHA-256",
            "code_verifier": attempt.verifier,
            "use_global_authentication": false,
        },
        "registration_data": {
            "app_name": APP_NAME,
            "app_version": APP_VERSION,
            "device_model": DEVICE_MODEL,
            "device_name": null,
            "device_serial": attempt.serial,
            "device_type": DEVICE_TYPE,
            "domain": REGISTRATION_DOMAIN,
            "os_version": OS_VERSION,
        },
        "requested_extensions": ["customer_info", "device_info"],
        "requested_token_type": ["bearer", "mac_dms"],
        "user_context_map": {},
    })
}

pub fn read_registration(answer: &Value, attempt: &Attempt, now_ms: i64) -> Option<Registered> {
    let success = answer.get("response")?.get("success")?;
    let bearer = success.get("tokens")?.get("bearer")?;
    let access_token = net::str_of(bearer, "access_token");
    let refresh_token = net::str_of(bearer, "refresh_token");
    if access_token.is_empty() || refresh_token.is_empty() {
        return None;
    }
    let serial = net::str_at(success, &["extensions", "device_info", "device_serial_number"]);
    Some(Registered {
        access_token: access_token.to_string(),
        refresh_token: refresh_token.to_string(),
        expires_at_ms: now_ms + expires_in(bearer.get("expires_in")) * 1000,
        device_serial: if serial.is_empty() { attempt.serial.clone() } else { serial.to_string() },
        client_id: attempt.client_id.clone(),
        display_name: net::str_at(success, &["extensions", "customer_info", "name"]).trim().to_string(),
    })
}

fn identity_call(url: &str, body: &Value) -> Call {
    Call::post_json(url, net::BROWSER_USER_AGENT.to_string(), body)
}

pub fn register(code: &str, attempt: &Attempt) -> Result<Registered, String> {
    let reply = net::send(identity_call(REGISTER_URL, &registration_body(code, attempt)))?;
    if !reply.ok() {
        return Err(format!("registration answered HTTP {}", reply.status));
    }
    let answer = reply.json().ok_or("registration answer is not JSON")?;
    read_registration(&answer, attempt, net::now_ms()).ok_or_else(|| "registration answer has no tokens".into())
}

pub fn refresh(refresh_token: &str) -> Result<(String, i64), String> {
    let body = json!({
        "source_token": refresh_token,
        "source_token_type": "refresh_token",
        "requested_token_type": "access_token",
        "app_name": APP_NAME,
        "app_version": APP_VERSION,
    });
    let call = identity_call(TOKEN_URL, &body).header("x-amzn-identity-auth-domain", "api.amazon.com");
    let reply = net::send(call)?;
    if !reply.ok() {
        return Err(format!("token refresh answered HTTP {}", reply.status));
    }
    let answer = reply.json().ok_or("token answer is not JSON")?;
    let access = net::str_of(&answer, "access_token");
    if access.is_empty() {
        return Err("token answer has no access token".into());
    }
    Ok((access.to_string(), net::now_ms() + expires_in(answer.get("expires_in")) * 1000))
}

pub fn deregister(access_token: &str) -> Result<(), String> {
    let body = json!({ "requested_extensions": ["device_info", "customer_info"] });
    let call = identity_call(DEREGISTER_URL, &body).header("Authorization", &format!("Bearer {access_token}"));
    net::send(call).map(|_| ())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn attempt() -> Attempt {
        let serial = serial_from(&[0xab; 16]);
        Attempt { client_id: client_id_for(&serial), serial, verifier: "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk".into() }
    }

    #[test]
    fn device_values() {
        let a = attempt();
        assert_eq!(a.serial, "ABABABABABAB4BABABABABABABABABAB");
        assert_eq!(a.serial.len(), 32);
        assert_eq!(a.client_id.len(), 94);
        assert_eq!(a.client_id, net::hex_lower(format!("{}#A2UMVHOX7UP4V7", a.serial).as_bytes()));
        assert!(a.client_id.starts_with("4142"));
        assert_eq!(challenge_for("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"), "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM");
        let fresh = Attempt::new().unwrap();
        assert_eq!(fresh.verifier.len(), 43);
        assert!(fresh.serial.chars().all(|c| c.is_ascii_hexdigit() && !c.is_ascii_lowercase()));
        assert_eq!(&fresh.serial[12..13], "4");
        assert_ne!(fresh, Attempt::new().unwrap());
    }

    #[test]
    fn sign_in_url() {
        let a = attempt();
        let url = a.start_url();
        assert!(url.starts_with("https://www.amazon.com/ap/signin?openid.ns=http%3A%2F%2Fspecs.openid.net%2Fauth%2F2.0&"));
        assert!(url.contains(&format!("&openid.oa2.client_id=device%3A{}&", a.client_id)));
        assert!(url.contains("&marketPlaceId=ATVPDKIKX0DER&"));
        assert!(url.contains("&openid.assoc_handle=amzn_sonic_games_launcher&pageId=amzn_sonic_games_launcher&"));
        assert!(url.ends_with("&openid.oa2.code_challenge=E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM"));
        assert_eq!(url.matches('&').count(), 16);
    }

    #[test]
    fn codes_from_return_urls() {
        assert_eq!(
            code_from_url("https://www.amazon.com/?openid.assoc_handle=x&openid.oa2.authorization_code=ANabc%2Bd&openid.mode=id_res").as_deref(),
            Some("ANabc+d")
        );
        assert_eq!(code_from_url("https://www.amazon.com/?openid.oa2.authorization_code=Z#frag").as_deref(), Some("Z"));
        assert_eq!(code_from_url("https://www.amazon.com/?openid.oa2.authorization_code=&x=1"), None);
        assert_eq!(code_from_url("https://www.amazon.com/?x.openid.oa2.authorization_code=1"), None);
        assert_eq!(code_from_url("https://www.amazon.com/"), None);
    }

    #[test]
    fn registration_round() {
        let a = attempt();
        let body = registration_body("CODE", &a);
        assert_eq!(body["auth_data"]["client_id"], a.client_id.as_str());
        assert_eq!(body["auth_data"]["use_global_authentication"], false);
        assert!(body["registration_data"]["device_name"].is_null());
        assert_eq!(body["registration_data"]["device_serial"], a.serial.as_str());
        let answer = json!({"response":{"success":{
            "tokens":{"bearer":{"access_token":"Atna|x","refresh_token":"Atnr|y","expires_in":"3600"},"mac_dms":{}},
            "extensions":{"customer_info":{"name":"Sam"},"device_info":{"device_serial_number":"SERVER"}}}}});
        let r = read_registration(&answer, &a, 1000).unwrap();
        assert_eq!(r.expires_at_ms, 3_601_000);
        assert_eq!(r.device_serial, "SERVER");
        assert_eq!(r.client_id, a.client_id);
        assert_eq!(r.to_json()["display_name"], "Sam");
        let bare = json!({"response":{"success":{"tokens":{"bearer":{"access_token":"A","refresh_token":"R"}}}}});
        let r = read_registration(&bare, &a, 0).unwrap();
        assert_eq!(r.device_serial, a.serial);
        assert_eq!(r.expires_at_ms, 3_600_000);
        assert!(r.to_json().get("display_name").is_none());
        assert!(read_registration(&json!({"response":{"error":{}}}), &a, 0).is_none());
    }
}
