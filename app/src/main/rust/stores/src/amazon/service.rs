use std::path::Path;

use serde_json::{json, Value};

use super::auth::{self, Attempt};
use super::distribution::{self, Failure};
use super::net::str_of;
use super::{cdn, install, launch, library, manifest};

fn error(message: impl Into<String>) -> Value {
    json!({ "error": message.into() })
}

pub fn call(op: &str, request: &str) -> String {
    let req: Value = serde_json::from_str(request).unwrap_or(Value::Null);
    dispatch(op, &req).to_string()
}

fn dispatch(op: &str, req: &Value) -> Value {
    let token = str_of(req, "accessToken");
    match op {
        "auth.start" => match Attempt::new() {
            Ok(a) => json!({
                "startUrl": a.start_url(),
                "userAgent": super::net::BROWSER_USER_AGENT,
                "redirectMarker": auth::REDIRECT_MARKER,
                "serial": a.serial,
                "clientId": a.client_id,
                "verifier": a.verifier,
            }),
            Err(e) => error(e),
        },
        "auth.register" => {
            let code = match str_of(req, "code") {
                "" => auth::code_from_url(str_of(req, "url")),
                c => Some(c.to_string()),
            };
            let Some(code) = code else {
                return json!({ "error": "the sign-in page returned no code", "noCode": true });
            };
            let attempt = Attempt {
                serial: str_of(req, "serial").to_string(),
                client_id: str_of(req, "clientId").to_string(),
                verifier: str_of(req, "verifier").to_string(),
            };
            if attempt.serial.is_empty() || attempt.client_id.is_empty() || attempt.verifier.is_empty() {
                return error("the sign-in attempt is incomplete");
            }
            match auth::register(&code, &attempt) {
                Ok(r) => r.to_json(),
                Err(e) => error(e),
            }
        }
        "auth.refresh" => match auth::refresh(str_of(req, "refreshToken")) {
            Ok((access, expires_at)) => json!({ "access_token": access, "expires_at": expires_at }),
            Err(e) => error(e),
        },
        "auth.deregister" => match auth::deregister(token) {
            Ok(()) => json!({}),
            Err(e) => error(e),
        },
        "library.entitlements" => match library::list(token, str_of(req, "deviceSerial")) {
            Ok(all) => {
                let total = all.len();
                let games = library::visible(all);
                json!({ "total": total, "games": games.iter().map(library::Game::to_json).collect::<Vec<_>>() })
            }
            Err(Failure::SignIn) => json!({ "error": "Amazon refused the session", "signIn": true }),
            Err(Failure::Other(e)) => error(e),
        },
        "download.lookup" => match distribution::game_download(token, str_of(req, "entitlementId")) {
            Ok(d) => json!({ "downloadUrl": d.base_url, "versionId": d.version_id }),
            Err(f) => error(f.message()),
        },
        "download.size" => json!({ "bytes": install::size_of(token, str_of(req, "entitlementId")) }),
        "launch.spec" => {
            let dir = str_of(req, "gameDir");
            if dir.is_empty() {
                return error("no install folder");
            }
            let spec = launch::launch_spec(Path::new(dir), str_of(req, "title"));
            json!({ "exe": spec.exe, "args": spec.args, "source": spec.source, "warning": spec.warning })
        }
        "launch.env" => {
            let env = launch::fuel_environment(str_of(req, "entitlementId"), str_of(req, "sku"), str_of(req, "displayName"));
            json!({ "env": env.iter().map(|(k, v)| json!([k, v])).collect::<Vec<_>>() })
        }
        _ => error(format!("unknown operation {op}")),
    }
}

pub fn fetch(op: &str, request: &str) -> Option<Vec<u8>> {
    let req: Value = serde_json::from_str(request).ok()?;
    match op {
        "cdn.get" => {
            let url = str_of(&req, "url");
            if url.is_empty() {
                return None;
            }
            cdn::get(url, Some(str_of(&req, "accessToken"))).ok()
        }
        _ => None,
    }
}

pub fn inspect(bytes: &[u8], _request: &str) -> String {
    match manifest::parse(bytes) {
        Ok(m) => json!({ "totalBytes": m.total_size(), "files": m.files.len() }).to_string(),
        Err(e) => error(e).to_string(),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::amazon::codec::testing::frame;
    use crate::amazon::manifest::testing::{message, Entry};

    #[test]
    fn local_operations() {
        let v: Value = serde_json::from_str(&call("auth.start", "{}")).unwrap();
        assert!(v["startUrl"].as_str().unwrap().starts_with("https://www.amazon.com/ap/signin?"));
        assert_eq!(v["redirectMarker"], "openid.oa2.authorization_code=");
        assert!(v["userAgent"].as_str().unwrap().contains("Firefox"));
        assert_eq!(v["serial"].as_str().unwrap().len(), 32);
        let v: Value = serde_json::from_str(&call("auth.register", r#"{"url":"https://www.amazon.com/?a=1"}"#)).unwrap();
        assert_eq!(v["noCode"], true);
        let v: Value = serde_json::from_str(&call("auth.register", r#"{"code":"c"}"#)).unwrap();
        assert!(v["error"].is_string() && v.get("noCode").is_none());
        let v: Value = serde_json::from_str(&call("launch.env", r#"{"entitlementId":"e","sku":"s","displayName":""}"#)).unwrap();
        assert_eq!(v["env"][2], json!(["AMAZON_GAMES_FUEL_ENTITLEMENT_ID", "e"]));
        assert_eq!(v["env"][4][1], "Player");
        let v: Value = serde_json::from_str(&call("nope", "{}")).unwrap();
        assert!(v["error"].is_string());
        assert!(fetch("cdn.get", "{}").is_none());
    }

    #[test]
    fn inspect_sizes() {
        let msg = message(&[vec![
            Entry { path: "a", size: 10, algorithm: Some(0), digest: &[1; 32] },
            Entry { path: "b\\c", size: 5, algorithm: Some(0), digest: &[2; 32] },
        ]]);
        let v: Value = serde_json::from_str(&inspect(&frame(&msg, b"", true), "{}")).unwrap();
        assert_eq!(v["totalBytes"], 15);
        assert_eq!(v["files"], 2);
        let v: Value = serde_json::from_str(&inspect(b"xx", "{}")).unwrap();
        assert!(v["error"].is_string());
    }
}
