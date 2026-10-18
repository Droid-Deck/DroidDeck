use serde_json::{json, Value};

use super::net::{self, Call, Reply};

pub const ENTITLEMENTS_URL: &str = "https://gaming.amazon.com/api/distribution/entitlements";
pub const PUBLIC_URL: &str = "https://gaming.amazon.com/api/distribution/v2/public";

pub const ENTITLEMENTS_TARGET: &str =
    "com.amazon.animusdistributionservice.entitlement.AnimusEntitlementsService.GetEntitlements";
pub const GAME_DOWNLOAD_TARGET: &str =
    "com.amazon.animusdistributionservice.external.AnimusDistributionService.GetGameDownload";

#[derive(Debug, PartialEq, Eq)]
pub enum Failure {
    SignIn,
    Other(String),
}

impl Failure {
    pub fn message(&self) -> String {
        match self {
            Failure::SignIn => "Amazon refused the session".into(),
            Failure::Other(m) => m.clone(),
        }
    }
}

pub fn refused(reply: &Reply) -> bool {
    matches!(reply.status, 401 | 403)
        || (reply.status == 400 && String::from_utf8_lossy(&reply.body).contains("NotAuthorizedException"))
}

pub fn call(url: &str, target: &str, access_token: &str, body: &Value) -> Result<Value, Failure> {
    let call = Call::post_json(url, net::LAUNCHER_USER_AGENT.to_string(), body)
        .header("X-Amz-Target", target)
        .header("x-amzn-token", access_token)
        .header(net::LAUNCHER_PACKAGE.0, net::LAUNCHER_PACKAGE.1)
        .header("Content-Encoding", "amz-1.0");
    let reply = net::send(call).map_err(Failure::Other)?;
    if !reply.ok() {
        if refused(&reply) {
            return Err(Failure::SignIn);
        }
        return Err(Failure::Other(format!("Amazon answered HTTP {} ({})", reply.status, reply.snippet())));
    }
    reply.json().ok_or_else(|| Failure::Other("Amazon's answer could not be read".into()))
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Download {
    pub base_url: String,
    pub version_id: String,
}

pub fn read_download(answer: &Value) -> Option<Download> {
    let base_url = net::str_of(answer, "downloadUrl");
    if base_url.is_empty() {
        return None;
    }
    Some(Download { base_url: base_url.to_string(), version_id: net::str_of(answer, "versionId").to_string() })
}

pub fn game_download(access_token: &str, entitlement_id: &str) -> Result<Download, Failure> {
    let body = json!({ "entitlementId": entitlement_id, "Operation": "GetGameDownload" });
    let answer = call(PUBLIC_URL, GAME_DOWNLOAD_TARGET, access_token, &body)?;
    read_download(&answer).ok_or_else(|| Failure::Other("the answer has no download URL".into()))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn download_answers() {
        let d = read_download(&json!({"downloadUrl":"https://cdn/x?sig=1","versionId":"v7"})).unwrap();
        assert_eq!(d.base_url, "https://cdn/x?sig=1");
        assert_eq!(d.version_id, "v7");
        assert_eq!(read_download(&json!({"downloadUrl":"","versionId":"v"})), None);
        assert_eq!(read_download(&json!({})), None);
        assert_eq!(read_download(&json!({"downloadUrl":"https://cdn/x"})).unwrap().version_id, "");
    }

    #[test]
    fn refusals() {
        let r = |status, body: &str| Reply { status, body: body.as_bytes().to_vec() };
        assert!(refused(&r(401, "<NotAuthorizedException/>")));
        assert!(refused(&r(403, "")));
        assert!(refused(&r(400, r#"{"__type":"com.amazon.coral.service#NotAuthorizedException"}"#)));
        assert!(!refused(&r(400, r#"{"__type":"com.amazon.coral.validate#ValidationException"}"#)));
        assert!(!refused(&r(500, "")));
    }
}
