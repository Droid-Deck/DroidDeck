use serde_json::{json, Value};

use super::build_info::{self, BuildInfo};
use super::net::str_of;
use super::{account, achievements, catalog, install, launch, library, locale, manifest, savesync, shop};

fn error(message: impl Into<String>) -> Value {
    json!({ "error": message.into() })
}

fn info_of(req: &Value) -> Option<BuildInfo> {
    req.get("info").and_then(BuildInfo::from_json)
}

pub fn call(op: &str, request: &str) -> String {
    let req: Value = serde_json::from_str(request).unwrap_or(Value::Null);
    dispatch(op, &req).to_string()
}

fn dispatch(op: &str, req: &Value) -> Value {
    let token = str_of(req, "accessToken");
    match op {
        "auth.config" => json!({
            "startUrl": account::start_url(),
            "redirectPrefix": account::REDIRECT_PREFIX,
            "webViewUserAgent": account::WEBVIEW_USER_AGENT,
        }),
        "auth.codeFromPage" => match account::code_from_page_text(str_of(req, "text")) {
            Some(code) => json!({ "code": code }),
            None => json!({}),
        },
        "auth.signIn" => match account::sign_in_with_code(str_of(req, "code")) {
            Ok(t) => t.to_json(),
            Err(e) => error(e),
        },
        "auth.refresh" => match account::refresh(str_of(req, "refreshToken")) {
            Ok(t) => t.to_json(),
            Err(e) => error(e),
        },
        "auth.exchange" => match account::exchange_code(token) {
            Ok(code) => json!({ "code": code }),
            Err(e) => error(e),
        },
        "library.owned" => match library::list_owned(token) {
            Ok(list) => json!({ "records": list.iter().map(|o| json!({
                "appName": o.app_name, "namespace": o.namespace, "catalogItemId": o.catalog_item_id,
            })).collect::<Vec<_>>() }),
            Err(library::ListError::SignIn) => json!({ "error": "sign-in refused", "signIn": true }),
            Err(library::ListError::Failed(e)) => error(e),
        },
        "catalog.details" => match catalog::details(token, str_of(req, "namespace"), str_of(req, "catalogItemId")) {
            Some(d) => json!({ "details": d }),
            None => error("no catalog answer"),
        },
        "catalog.attribute" => match catalog::attribute(
            token,
            str_of(req, "namespace"),
            str_of(req, "catalogItemId"),
            str_of(req, "name"),
        ) {
            Some(v) => json!({ "value": v }),
            None => json!({ "unknown": true }),
        },
        "build.info" => match build_info::fetch(
            token,
            str_of(req, "namespace"),
            str_of(req, "catalogItemId"),
            str_of(req, "appName"),
        ) {
            Some(info) => json!({ "info": info.to_json() }),
            None => error("The manifest could not be fetched"),
        },
        "build.cdns" => json!({ "cdns": info_of(req).map(|i| build_info::cdns_to_json(&i.cdns)).unwrap_or(json!([])) }),
        "build.prereq" => match info_of(req).map(|i| install::fetch_manifest(&i, &|_: &str| {})) {
            Some(Ok(m)) => json!({ "name": m.prereq_name, "path": m.prereq_path, "read": m.prereq_read }),
            Some(Err(e)) => error(e),
            None => error("no build information"),
        },
        "shop.shelves" => shop::shelves(str_of(req, "country")),
        "achievements" => match achievements::fetch(
            token,
            str_of(req, "namespace"),
            str_of(req, "accountId"),
            str_of(req, "locale"),
        ) {
            Some(list) => json!({ "list": list }),
            None => error("unreachable"),
        },
        "cloud.list" => match savesync::list(token, str_of(req, "accountId"), str_of(req, "appName")) {
            Ok(files) => json!({ "files": files }),
            Err(e) => error(e),
        },
        "cloud.writeLinks" => {
            let names: Vec<String> = req
                .get("names")
                .and_then(|n| n.as_array())
                .map(|a| a.iter().filter_map(|n| n.as_str()).map(str::to_string).collect())
                .unwrap_or_default();
            match savesync::write_links(token, str_of(req, "accountId"), str_of(req, "appName"), &names) {
                Ok(links) => json!({ "links": links }),
                Err(e) => error(e),
            }
        }
        "launch.args" => {
            let data = launch::LaunchData {
                display_name: str_of(req, "displayName").into(),
                account_id: str_of(req, "accountId").into(),
                namespace: str_of(req, "namespace").into(),
                locale: str_of(req, "locale").into(),
                app_name: str_of(req, "appName").into(),
                deployment_id: str_of(req, "deploymentId").into(),
                additional: str_of(req, "additional").into(),
            };
            json!({ "args": launch::arguments(&data) })
        }
        "locale" => {
            let key = locale::language_key(str_of(req, "language"), str_of(req, "region"));
            json!({ "key": key, "tags": locale::tags_for_key(key), "launchLocale": locale::launch_locale(key) })
        }
        _ => error(format!("unknown operation {op}")),
    }
}

pub fn fetch(op: &str, request: &str) -> Option<Vec<u8>> {
    let req: Value = serde_json::from_str(request).ok()?;
    match op {
        "manifest.download" => {
            let info = info_of(&req)?;
            let cdns = req.get("cdns").map(build_info::cdns_from_json).unwrap_or_else(|| info.cdns.clone());
            build_info::download_manifest(&info, &cdns, &|_: &str| {})
        }
        "cloud.read" => savesync::read(str_of(&req, "url")).ok(),
        _ => None,
    }
}

pub fn send(op: &str, request: &str, body: Vec<u8>) -> String {
    let req: Value = serde_json::from_str(request).unwrap_or(Value::Null);
    let out = match op {
        "cloud.write" => match savesync::write(str_of(&req, "url"), body) {
            Ok(()) => json!({ "ok": true }),
            Err(e) => error(e),
        },
        _ => error(format!("unknown operation {op}")),
    };
    out.to_string()
}

pub fn inspect(bytes: &[u8], request: &str) -> String {
    let req: Value = serde_json::from_str(request).unwrap_or(Value::Null);
    let m = match manifest::parse(bytes) {
        Ok(m) => m,
        Err(e) => return error(e).to_string(),
    };
    let out = match str_of(&req, "op") {
        "select" => {
            let tags: Option<Vec<String>> = req
                .get("tags")
                .and_then(|t| t.as_array())
                .map(|a| a.iter().filter_map(|t| t.as_str()).map(str::to_string).collect());
            json!({ "indices": locale::select(&m.files, tags.as_deref()) })
        }
        _ => json!({
            "buildVersion": m.build_version,
            "launchExe": m.launch_exe,
            "prereqName": m.prereq_name,
            "prereqPath": m.prereq_path,
            "prereqRead": m.prereq_read,
            "files": m.files.iter().map(|f| json!({ "path": f.path, "size": f.size, "tags": f.tags })).collect::<Vec<_>>(),
        }),
    };
    out.to_string()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn local_operations() {
        let v: Value = serde_json::from_str(&call("locale", r#"{"language":"pt","region":"BR"}"#)).unwrap();
        assert_eq!(v["key"], "pt-BR");
        assert_eq!(v["launchLocale"], "pt");
        let v: Value = serde_json::from_str(&call("auth.codeFromPage", r#"{"text":"{\"authorizationCode\":\"c1\"}"}"#)).unwrap();
        assert_eq!(v["code"], "c1");
        let v: Value = serde_json::from_str(&call("nope", "{}")).unwrap();
        assert!(v["error"].is_string());
        let v: Value = serde_json::from_str(&call("launch.args", r#"{"namespace":"ns","appName":"A"}"#)).unwrap();
        assert_eq!(v["args"][0], "-EpicPortal");
    }

    #[test]
    fn inspect_lists_and_selects() {
        use crate::epic::manifest::testing::*;
        let m = TestManifest {
            version: 21,
            build: "b".into(),
            exe: "x.exe".into(),
            prereq: None,
            chunks: vec![],
            files: vec![
                TestFile { path: "x.exe".into(), sha1: [0; 20], tags: vec![], parts: vec![] },
                TestFile { path: "de.pak".into(), sha1: [0; 20], tags: vec!["German".into()], parts: vec![] },
            ],
            compress: false,
            meta_tail: vec![],
        };
        let bytes = build(&m);
        let v: Value = serde_json::from_str(&inspect(&bytes, "{}")).unwrap();
        assert_eq!(v["files"].as_array().unwrap().len(), 2);
        let v: Value = serde_json::from_str(&inspect(&bytes, r#"{"op":"select","tags":["English"]}"#)).unwrap();
        assert_eq!(v["indices"], json!([0]));
        let v: Value = serde_json::from_str(&inspect(b"nonsense", "{}")).unwrap();
        assert!(v["error"].is_string());
    }
}
