//! The one process-wide Steam session: connect to a CM over WebSocket, log on with the saved
//! refresh token, then answer the store's requests (PICS, depot keys, manifest request codes,
//! CDN servers, free licenses) as blocking calls with timeouts.

use crate::steam::cm_client::{
    CMClientCore, ClientState, OutboundProtoMessage, OutboundServiceCall,
};
use crate::steam::cm_runtime::CMClientRuntime;
use crate::steam::library::Library;
use crate::steam::pb::ccontentserverdirectory::{
    CContentServerDirectoryGetManifestRequestCodeResponse,
    CContentServerDirectoryGetServersForSteamPipeResponse, CContentServerDirectoryServerInfo,
};
use crate::steam::pb::cmsg_client_get_depot_decryption_key::CMsgClientGetDepotDecryptionKeyResponse;
use crate::steam::pb::cmsg_client_pics::{
    CMsgClientPICSAccessTokenResponse, CMsgClientPICSProductInfoResponse, PicsAppInfoReq,
    PicsPackageInfoReq,
};
use crate::steam::pb::cmsg_client_request_free_license::CMsgClientRequestFreeLicenseResponse;
use crate::steam::signin::SavedLogin;
use crate::steam::vdf::KVNode;
use crate::steam::ws_connection::WsConnection;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{mpsc, Arc, Mutex};
use std::thread;
use std::time::{Duration, Instant};

pub const REQUEST_TIMEOUT: Duration = Duration::from_secs(30);
const CONNECT_TIMEOUT: Duration = Duration::from_secs(15);
const LOGON_TIMEOUT: Duration = Duration::from_secs(20);
const LICENSE_LIST_TIMEOUT: Duration = Duration::from_secs(10);
const MAX_CM_ATTEMPTS: usize = 4;
const MAX_RESOLVE_ATTEMPTS: u32 = 4;
const ERESULT_OK: i32 = 1;
/// EResults after which another CM is worth a try (the token itself was not refused).
const RETRY_ON_ANOTHER_CM: [i32; 3] = [20, 48, 3];

pub struct Session {
    runtime: Arc<CMClientRuntime>,
    pub account: String,
    pub steam_id: u64,
    pub ca_bundle_path: String,
    library: Mutex<Option<Arc<Library>>>,
}

static SESSION: Mutex<Option<Arc<Session>>> = Mutex::new(None);

pub fn eresult_name(eresult: i32) -> &'static str {
    match eresult {
        1 => "OK",
        2 => "Fail",
        3 => "NoConnection",
        5 => "InvalidPassword",
        6 => "LoggedInElsewhere",
        15 => "AccessDenied",
        18 => "AccountNotFound",
        20 => "ServiceUnavailable",
        26 => "Revoked",
        27 => "Expired",
        48 => "TryAnotherCM",
        63 => "AccountLogonDenied",
        84 => "RateLimitExceeded",
        _ => "",
    }
}

/// The live session, if one is logged on.
pub fn current() -> Option<Arc<Session>> {
    let guard = SESSION.lock().expect("session poisoned");
    guard.as_ref().filter(|s| s.is_logged_on()).cloned()
}

/// Logs on with `login` unless a live session exists; returns the live session.
pub fn sign_in(login: &SavedLogin, ca_bundle_path: &str) -> Result<Arc<Session>, String> {
    if let Some(live) = current() {
        return Ok(live);
    }
    sign_out();
    let runtime = connect_and_logon(login, ca_bundle_path)?;
    let steam_id = runtime.core().steam_id();
    let session = Arc::new(Session {
        runtime,
        account: login.account.clone(),
        steam_id: if steam_id != 0 {
            steam_id
        } else {
            login.steam_id
        },
        ca_bundle_path: ca_bundle_path.to_string(),
        library: Mutex::new(None),
    });
    *SESSION.lock().expect("session poisoned") = Some(Arc::clone(&session));
    Ok(session)
}

/// Logs off and drops the session. Never touches the Steam client's files.
pub fn sign_out() {
    let previous = SESSION.lock().expect("session poisoned").take();
    if let Some(session) = previous {
        session.log_off();
    }
}

fn cm_urls(ca_bundle_path: &str) -> Vec<String> {
    let directory = crate::steam::steam_directory::SteamDirectoryClient::new(ca_bundle_path);
    let result = directory.fetch(0, crate::steam::steam_directory::DEFAULT_TIMEOUT);
    let mut urls: Vec<String> = Vec::new();
    let servers = if result.ok() {
        result.servers
    } else {
        crate::steam::log(&format!("cm directory failed: {}", result.error));
        Vec::new()
    };
    for server in servers
        .into_iter()
        .chain(crate::steam::cm_server_list::hardcoded_fallback_servers())
    {
        let url = server.websocket_url();
        if !url.is_empty() && !urls.contains(&url) {
            urls.push(url);
        }
    }
    urls
}

fn wait_until(timeout: Duration, mut done: impl FnMut() -> bool) -> bool {
    let deadline = Instant::now() + timeout;
    loop {
        if done() {
            return true;
        }
        if Instant::now() >= deadline {
            return false;
        }
        thread::sleep(Duration::from_millis(50));
    }
}

fn connect_and_logon(
    login: &SavedLogin,
    ca_bundle_path: &str,
) -> Result<Arc<CMClientRuntime>, String> {
    let urls = cm_urls(ca_bundle_path);
    let mut last_error = "logon_failed: no CM server".to_string();
    for url in urls.iter().take(MAX_CM_ATTEMPTS) {
        let core = Arc::new(CMClientCore::default());
        let runtime = CMClientRuntime::new(Arc::clone(&core), Box::new(WsConnection::new()));
        if !ca_bundle_path.is_empty() {
            runtime.set_ca_bundle_path(ca_bundle_path);
        }
        if !runtime.connect(url) {
            last_error = "logon_failed: connect".to_string();
            continue;
        }
        if !wait_until(CONNECT_TIMEOUT, || core.state() == ClientState::Connected) {
            runtime.disconnect();
            last_error = "logon_failed: CM did not answer".to_string();
            continue;
        }
        let message =
            core.build_logon_with_refresh_token(&login.token, &login.account, login.steam_id);
        if !core.enqueue_proto_message(message) {
            runtime.disconnect();
            last_error = "logon_failed: connection dropped".to_string();
            continue;
        }
        runtime.flush_outbound();
        let settled = wait_until(LOGON_TIMEOUT, || {
            core.state() == ClientState::LoggedOn
                || core.last_logon_eresult() != 0
                || core.state() == ClientState::Disconnected
        });
        if core.state() == ClientState::LoggedOn {
            crate::steam::log(&format!(
                "logged on steam_id={} cell={}",
                core.steam_id(),
                core.cell_id()
            ));
            wait_until(LICENSE_LIST_TIMEOUT, || core.license_list().is_some());
            return Ok(runtime);
        }
        let eresult = core.last_logon_eresult();
        runtime.disconnect();
        if !settled {
            last_error = "logon_failed: no logon response".to_string();
            continue;
        }
        if eresult == 0 {
            last_error = "logon_failed: connection dropped".to_string();
            continue;
        }
        last_error = format!("logon_failed: eresult={eresult} {}", eresult_name(eresult))
            .trim_end()
            .to_string();
        crate::steam::log(&last_error);
        if !RETRY_ON_ANOTHER_CM.contains(&eresult) {
            break;
        }
    }
    Err(last_error)
}

pub enum KeyResolution {
    Granted(Vec<u8>),
    AccessDenied,
    Cancelled,
    Unavailable,
}

pub enum CodeResolution {
    Code(u64),
    AccessDenied,
    Cancelled,
    Unavailable,
}

impl Session {
    pub fn is_logged_on(&self) -> bool {
        self.runtime.core().state() == ClientState::LoggedOn
    }

    fn log_off(&self) {
        let core = self.runtime.core();
        if core.enqueue_proto_message(core.build_logoff()) {
            self.runtime.flush_outbound();
            thread::sleep(Duration::from_millis(500));
        }
        self.runtime.disconnect();
    }

    pub fn core(&self) -> &Arc<CMClientCore> {
        self.runtime.core()
    }

    fn proto_response<F>(&self, timeout: Duration, build: F) -> Option<(i32, Vec<u8>)>
    where
        F: FnOnce(&CMClientCore, u64) -> Option<OutboundProtoMessage>,
    {
        if !self.is_logged_on() {
            return None;
        }
        let job_id = self.runtime.next_job_id();
        let message = build(self.runtime.core(), job_id)?;
        let (tx, rx) = mpsc::channel();
        self.runtime.track_job(
            job_id,
            move |job| {
                let _ = tx.send((!job.synthetic_failure).then_some((job.eresult, job.body)));
            },
            Some(timeout),
        );
        if !self.runtime.core().enqueue_wire(message.wire) {
            return None;
        }
        self.runtime.flush_outbound();
        rx.recv_timeout(timeout + Duration::from_secs(1))
            .ok()
            .flatten()
    }

    fn service_response<F>(&self, timeout: Duration, build: F) -> Option<(i32, Vec<u8>)>
    where
        F: FnOnce(&CMClientCore, u64) -> Option<OutboundServiceCall>,
    {
        if !self.is_logged_on() {
            return None;
        }
        let job_id = self.runtime.next_job_id();
        let call = build(self.runtime.core(), job_id)?;
        let (tx, rx) = mpsc::channel();
        self.runtime.track_job(
            job_id,
            move |job| {
                let _ = tx.send((!job.synthetic_failure).then_some((job.eresult, job.body)));
            },
            Some(timeout),
        );
        if !self.runtime.core().enqueue_wire(call.wire) {
            return None;
        }
        self.runtime.flush_outbound();
        rx.recv_timeout(timeout + Duration::from_secs(1))
            .ok()
            .flatten()
    }

    pub fn pics_access_tokens(
        &self,
        packages: Vec<u32>,
        apps: Vec<u32>,
    ) -> Option<CMsgClientPICSAccessTokenResponse> {
        let (eresult, body) = self.proto_response(REQUEST_TIMEOUT, |core, job| {
            core.build_pics_access_tokens(packages, apps, job)
        })?;
        if eresult <= 0 {
            return None;
        }
        CMsgClientPICSAccessTokenResponse::deserialize(&body)
    }

    /// PICS product info; large appinfo the CM delivers over HTTP is fetched here.
    pub fn pics_product_info(
        &self,
        packages: Vec<PicsPackageInfoReq>,
        apps: Vec<PicsAppInfoReq>,
    ) -> Option<CMsgClientPICSProductInfoResponse> {
        let (eresult, body) = self.proto_response(Duration::from_secs(60), |core, job| {
            core.build_pics_product_info(packages, apps, false, job)
        })?;
        if eresult <= 0 {
            return None;
        }
        let mut response = CMsgClientPICSProductInfoResponse::deserialize(&body)?;
        self.hydrate_http_appinfo(&mut response);
        Some(response)
    }

    fn hydrate_http_appinfo(&self, response: &mut CMsgClientPICSProductInfoResponse) {
        if response.http_host.is_empty() {
            return;
        }
        let host = response.http_host.clone();
        let targets: Vec<usize> = response
            .apps
            .iter()
            .enumerate()
            .filter(|(_, app)| !app.missing_token && app.buffer.is_empty() && !app.sha.is_empty())
            .map(|(idx, _)| idx)
            .collect();
        if targets.is_empty() {
            return;
        }
        let queue = Mutex::new(targets.into_iter());
        let results: Mutex<Vec<(usize, Vec<u8>)>> = Mutex::new(Vec::new());
        let apps = &response.apps;
        let workers = queue.lock().expect("queue poisoned").len().clamp(1, 8);
        thread::scope(|scope| {
            for _ in 0..workers {
                scope.spawn(|| loop {
                    let next = queue.lock().expect("queue poisoned").next();
                    let Some(idx) = next else {
                        return;
                    };
                    let app = &apps[idx];
                    if let Some(buffer) = crate::steam::cdn::fetch_appinfo(
                        &host,
                        app.appid,
                        &app.sha,
                        &self.ca_bundle_path,
                        REQUEST_TIMEOUT,
                    ) {
                        results
                            .lock()
                            .expect("results poisoned")
                            .push((idx, buffer));
                    }
                });
            }
        });
        for (idx, buffer) in results.into_inner().expect("results poisoned") {
            response.apps[idx].buffer = buffer;
        }
    }

    /// The app's appinfo (`appinfo` node), with its PICS access token.
    pub fn app_info(&self, app_id: u32) -> Option<KVNode> {
        let token = self
            .pics_access_tokens(Vec::new(), vec![app_id])
            .and_then(|r| {
                r.app_access_tokens
                    .iter()
                    .find(|t| t.appid == app_id)
                    .map(|t| t.access_token)
            })
            .unwrap_or(0);
        let response = self.pics_product_info(
            Vec::new(),
            vec![PicsAppInfoReq {
                appid: app_id,
                access_token: token,
                only_public_obsolete: false,
            }],
        )?;
        let app = response
            .apps
            .into_iter()
            .find(|a| a.appid == app_id && !a.buffer.is_empty())?;
        let root = crate::steam::vdf::parse_auto(&app.buffer)?;
        Some(crate::steam::plan::appinfo_root(&root).clone())
    }

    pub fn library(&self) -> Result<Arc<Library>, String> {
        if let Some(lib) = self.library.lock().expect("library poisoned").as_ref() {
            return Ok(Arc::clone(lib));
        }
        let lib = Arc::new(Library::load(self)?);
        *self.library.lock().expect("library poisoned") = Some(Arc::clone(&lib));
        Ok(lib)
    }

    /// The owned games, loaded once per session.
    pub fn games(&self) -> Result<Vec<crate::steam::library::Game>, String> {
        let lib = self.library()?;
        if let Some(games) = lib.cached_games() {
            return Ok(games);
        }
        let games = lib.load_games(self)?;
        *self.library.lock().expect("library poisoned") =
            Some(Arc::new(lib.with_games(games.clone())));
        Ok(games)
    }

    pub fn forget_library(&self) {
        *self.library.lock().expect("library poisoned") = None;
    }

    /// `CMsgClientRequestFreeLicense` for one app; true when Steam granted it and the license
    /// list shows the new package.
    pub fn request_free_license(&self, app_id: u32) -> bool {
        let Some((_header, body)) = self.proto_response(Duration::from_secs(20), |core, job| {
            core.build_request_free_license(&[app_id], job)
        }) else {
            return false;
        };
        let Some(response) = CMsgClientRequestFreeLicenseResponse::deserialize(&body) else {
            return false;
        };
        let granted = response.is_granted();
        crate::steam::log(&format!(
            "free license app={app_id} eresult={} packages={:?}",
            response.eresult, response.granted_packageids
        ));
        if !granted {
            return false;
        }
        let packages = response.granted_packageids.clone();
        let core = self.runtime.core();
        let visible = wait_until(Duration::from_secs(8), || {
            core.license_list().is_some_and(|list| {
                packages
                    .iter()
                    .all(|p| list.iter().any(|l| l.package_id == *p))
            })
        });
        self.forget_library();
        visible || packages.is_empty()
    }

    pub fn cdn_servers(&self) -> Option<Vec<CContentServerDirectoryServerInfo>> {
        let (eresult, body) = self.service_response(REQUEST_TIMEOUT, |core, job| {
            core.build_get_cdn_servers_call(0, job)
        })?;
        if eresult != ERESULT_OK {
            return None;
        }
        let servers =
            CContentServerDirectoryGetServersForSteamPipeResponse::deserialize(&body)?.servers;
        let preview = servers
            .iter()
            .take(4)
            .map(|s| s.host.as_str())
            .collect::<Vec<_>>()
            .join(",");
        crate::steam::log(&format!(
            "cdn pool: {} server(s) first=[{preview}]",
            servers.len()
        ));
        Some(servers)
    }

    pub fn depot_key(&self, app_id: u32, depot_id: u32, cancel: &AtomicBool) -> KeyResolution {
        for attempt in 0..MAX_RESOLVE_ATTEMPTS {
            if cancel.load(Ordering::Relaxed) {
                return KeyResolution::Cancelled;
            }
            if attempt > 0 {
                thread::sleep(Duration::from_millis(
                    crate::fetch_tuning::retry_backoff_millis(attempt),
                ));
            }
            let Some((header, body)) = self.proto_response(REQUEST_TIMEOUT, |core, job| {
                core.build_get_depot_decryption_key(depot_id, app_id, job)
            }) else {
                continue;
            };
            let response = CMsgClientGetDepotDecryptionKeyResponse::deserialize(&body);
            if let Some(r) = &response {
                if r.eresult == ERESULT_OK as u32 && r.depot_encryption_key.len() == 32 {
                    return KeyResolution::Granted(r.depot_encryption_key.clone());
                }
            }
            crate::steam::log(&format!(
                "depot key refused depot={depot_id} header_eresult={header} body_eresult={}",
                response.map(|r| r.eresult as i64).unwrap_or(-1)
            ));
            return KeyResolution::AccessDenied;
        }
        KeyResolution::Unavailable
    }

    pub fn manifest_code(
        &self,
        app_id: u32,
        depot_id: u32,
        manifest_id: u64,
        cancel: &AtomicBool,
    ) -> CodeResolution {
        for attempt in 0..MAX_RESOLVE_ATTEMPTS {
            if cancel.load(Ordering::Relaxed) {
                return CodeResolution::Cancelled;
            }
            if attempt > 0 {
                thread::sleep(Duration::from_millis(
                    crate::fetch_tuning::retry_backoff_millis(attempt),
                ));
            }
            let Some((eresult, body)) = self.service_response(REQUEST_TIMEOUT, |core, job| {
                core.build_manifest_request_code_call(app_id, depot_id, manifest_id, "public", job)
            }) else {
                continue;
            };
            if eresult == ERESULT_OK {
                match CContentServerDirectoryGetManifestRequestCodeResponse::deserialize(&body) {
                    Some(r) => return CodeResolution::Code(r.manifest_request_code),
                    None => continue,
                }
            }
            crate::steam::log(&format!(
                "manifest code refused depot={depot_id} eresult={eresult}"
            ));
            return CodeResolution::AccessDenied;
        }
        CodeResolution::Unavailable
    }
}
