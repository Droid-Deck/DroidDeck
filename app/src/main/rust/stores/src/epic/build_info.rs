use std::time::Duration;

use serde_json::{json, Value};

use super::net::{self, Request};

const LAUNCHER_HOST: &str = "https://launcher-public-service-prod06.ol.epicgames.com";
const EXCLUDED_CDN: &str = "cloudflare.epicgamescdn.com";

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Cdn {
    pub base: String,
    pub dir: String,
    pub auth: String,
}

impl Cdn {
    pub fn prefix(&self) -> String {
        format!("{}{}", self.base, self.dir)
    }
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct BuildInfo {
    pub cdns: Vec<Cdn>,
    pub manifest_file: String,
    pub build_version: String,
    pub deployment_id: String,
}

impl BuildInfo {
    pub fn to_json(&self) -> Value {
        json!({
            "v": 1,
            "manifest": self.manifest_file,
            "buildVersion": self.build_version,
            "deploymentId": self.deployment_id,
            "cdns": cdns_to_json(&self.cdns),
        })
    }

    pub fn from_json(v: &Value) -> Option<BuildInfo> {
        Some(BuildInfo {
            cdns: cdns_from_json(v.get("cdns")?),
            manifest_file: net::str_of(v, "manifest").to_string(),
            build_version: net::str_of(v, "buildVersion").to_string(),
            deployment_id: net::str_of(v, "deploymentId").to_string(),
        })
    }
}

pub fn cdns_to_json(cdns: &[Cdn]) -> Value {
    Value::Array(cdns.iter().map(|c| json!({"base": c.base, "dir": c.dir, "auth": c.auth})).collect())
}

pub fn cdns_from_json(v: &Value) -> Vec<Cdn> {
    v.as_array()
        .map(|a| {
            a.iter()
                .map(|c| Cdn {
                    base: net::str_of(c, "base").to_string(),
                    dir: net::str_of(c, "dir").to_string(),
                    auth: net::str_of(c, "auth").to_string(),
                })
                .filter(|c| !c.base.is_empty())
                .collect()
        })
        .unwrap_or_default()
}

pub fn cdn_of(entry: &Value) -> Option<Cdn> {
    let uri = net::str_of(entry, "uri");
    let at = uri.find("/Builds")?;
    let base = &uri[..at];
    if !base.starts_with("http") || base.contains(EXCLUDED_CDN) {
        return None;
    }
    let path = &uri[at..];
    let path = path.split('?').next().unwrap_or(path);
    let dir = &path[..path.rfind('/').unwrap_or(path.len())];
    let params: Vec<String> = entry
        .get("queryParams")
        .and_then(|q| q.as_array())
        .map(|a| {
            a.iter()
                .map(|p| format!("{}={}", net::str_of(p, "name"), net::str_of(p, "value")))
                .collect()
        })
        .unwrap_or_default();
    let auth = if params.is_empty() { String::new() } else { format!("?{}", params.join("&")) };
    Some(Cdn { base: base.to_string(), dir: dir.to_string(), auth })
}

pub fn parse_answer(answer: &Value) -> Option<BuildInfo> {
    let element = answer.get("elements")?.as_array()?.first()?;
    let manifests = element.get("manifests").and_then(|m| m.as_array());
    let cdns: Vec<Cdn> = manifests.map(|m| m.iter().filter_map(cdn_of).collect()).unwrap_or_default();
    let manifest_file = manifests
        .and_then(|m| m.first())
        .map(|m| net::str_of(m, "uri"))
        .map(|u| u.split('?').next().unwrap_or(u))
        .map(|u| u.rsplit('/').next().unwrap_or("").to_string())
        .unwrap_or_default();
    let build_version = Some(net::str_of(element, "buildVersion"))
        .filter(|s| !s.is_empty())
        .unwrap_or_else(|| net::str_of(element, "versionId"))
        .to_string();
    let deployment_id = element
        .get("sidecar")
        .and_then(|s| s.get("config"))
        .and_then(|c| c.as_str())
        .and_then(|c| serde_json::from_str::<Value>(c).ok())
        .map(|c| net::str_of(&c, "deploymentId").to_string())
        .unwrap_or_default();
    Some(BuildInfo { cdns, manifest_file, build_version, deployment_id })
}

pub fn fetch(access_token: &str, namespace: &str, catalog_item_id: &str, app_name: &str) -> Option<BuildInfo> {
    let url = format!(
        "{LAUNCHER_HOST}/launcher/api/public/assets/v2/platform/Windows/namespace/{}/catalogItem/{}/app/{}/label/Live",
        net::percent_encode(namespace),
        net::percent_encode(catalog_item_id),
        net::percent_encode(app_name)
    );
    let reply = net::send(Request::get(&url).bearer(access_token)).ok()?;
    if !reply.ok() {
        return None;
    }
    parse_answer(&reply.json()?)
}

pub fn chunk_prefixes(cdns: &[Cdn]) -> Vec<String> {
    let mut out: Vec<String> = Vec::new();
    for c in cdns {
        let p = c.prefix().replace("\\/", "/").replace('\\', "");
        let p = p.trim_end_matches('/').to_string();
        if !p.is_empty() && !out.contains(&p) {
            out.push(p);
        }
    }
    out
}

pub fn download_manifest(info: &BuildInfo, cdns: &[Cdn], log: &dyn Fn(&str)) -> Option<Vec<u8>> {
    if info.manifest_file.is_empty() {
        return None;
    }
    for cdn in cdns {
        let url = format!("{}{}/{}{}", cdn.base, cdn.dir, info.manifest_file, cdn.auth);
        match net::send(Request::get(&url).read_timeout(Duration::from_secs(120))) {
            Ok(reply) if reply.status == 200 && reply.body.len() > 4 => return Some(reply.body),
            Ok(reply) => log(&format!("manifest from {}: HTTP {}", cdn.base, reply.status)),
            Err(e) => log(&format!("manifest from {}: {e}", cdn.base)),
        }
    }
    None
}

#[cfg(test)]
mod tests {
    use super::*;

    fn answer() -> Value {
        json!({"elements": [{
            "appName": "Fig", "buildVersion": "1.0.5",
            "manifests": [
                {"uri": "https://download.epicgames.com/Builds/Org/o-x/abc/default/HASH.manifest?ignored=1",
                 "queryParams": [{"name": "f_token", "value": "tok=="}, {"name": "x", "value": "1"}]},
                {"uri": "https://cloudflare.epicgamescdn.com/Builds/Org/o-x/abc/default/HASH.manifest"},
                {"uri": "https://fastly-download.epicgames.com/Builds/Org/o-x/abc/default/HASH.manifest"},
                {"uri": "no-builds-here"}
            ],
            "sidecar": {"config": "{\"deploymentId\":\"dep42\"}"}
        }]})
    }

    #[test]
    fn cdn_list_and_manifest_name() {
        let info = parse_answer(&answer()).unwrap();
        assert_eq!(info.manifest_file, "HASH.manifest");
        assert_eq!(info.build_version, "1.0.5");
        assert_eq!(info.deployment_id, "dep42");
        assert_eq!(info.cdns.len(), 2);
        assert_eq!(info.cdns[0].base, "https://download.epicgames.com");
        assert_eq!(info.cdns[0].dir, "/Builds/Org/o-x/abc/default");
        assert_eq!(info.cdns[0].auth, "?f_token=tok==&x=1");
        assert_eq!(info.cdns[1].auth, "");
        let back = BuildInfo::from_json(&info.to_json()).unwrap();
        assert_eq!(back, info);
    }

    #[test]
    fn missing_elements_is_no_answer() {
        assert!(parse_answer(&json!({"elements": []})).is_none());
        assert!(parse_answer(&json!({})).is_none());
        let v = parse_answer(&json!({"elements": [{"versionId": "v9"}]})).unwrap();
        assert_eq!(v.build_version, "v9");
        assert!(v.cdns.is_empty());
    }

    #[test]
    fn chunk_prefixes_are_normalised() {
        let cdns = vec![
            Cdn { base: "https://a.example".into(), dir: "\\/Builds\\/x/".into(), auth: String::new() },
            Cdn { base: "https://a.example".into(), dir: "/Builds/x".into(), auth: "?s=1".into() },
            Cdn { base: "https://b.example".into(), dir: "/Builds/x".into(), auth: String::new() },
        ];
        assert_eq!(chunk_prefixes(&cdns), vec!["https://a.example/Builds/x", "https://b.example/Builds/x"]);
    }
}
