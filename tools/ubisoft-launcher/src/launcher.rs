//! What Ubisoft Connect answers on its local pipes, built from the launch configuration.
//!
//! Field numbers follow `mg.protocol.uplaydll` and `game_starter` as embedded in Ubisoft's own
//! `uplay_r1.dll`; see docs/development/ubisoft-launch.md.

use crate::pb::{self, Msg, Value};
use serde_json::Value as Json;

pub const GAME_START: &str = "game_start_ipc_pipe_";
pub const API_PROCESS: &str = "uplay_api_process_ipc_pipe_";
pub const SESSION: &str = "uplay_ipc_pipe_";
pub const AUX: &str = "uplay_aux_ipc_pipe_";
pub const SERVICE: &str = "uplay_service_ipc_pipe_";
/// Pipes Connect creates that the game may open; they are held open without answers.
pub const QUIET: &[&str] = &[
    "upc_api_process_ipc_pipe_standard",
    "uplay_protocol_ipc_pipe_",
    "uplay_overlay_ipc_pipe_",
    "orbit_ipc_pipe_",
    "uplay_crash_reporter_ipc_pipe_",
];
/// The add-on list Connect returns from the aux init; the game echoes it back.
const AUX_ADDONS: [u64; 3] = [3367, 3368, 3962];
/// `OverlayInjectionMethod_None`.
const OVERLAY_NONE: u64 = 1;
const HWSCORE: &str = "C:/ProgramData/Ubisoft/Ubisoft Game Launcher/cache//statistics/hwscore";

pub struct Config {
    pub product: u32,
    pub exe: String,
    pub args: String,
    pub workdir: String,
    pub user_id: String,
    pub username: String,
    pub name_on_platform: String,
    pub email: String,
    pub country: String,
    pub ticket: String,
    pub owned: Vec<u32>,
    pub app_id: String,
    pub saves: String,
    pub language: String,
    pub bridge: String,
    pub log: String,
    /// Installed chunks of the game's manifest: id and tags.
    pub chunks: Vec<(u32, String)>,
    /// Owned products and their ownership type (0 game, 1 add-on).
    pub products: Vec<(u32, u32)>,
}

fn s(j: &Json, k: &str) -> String {
    j.get(k).and_then(|v| v.as_str()).unwrap_or_default().to_string()
}

impl Config {
    pub fn parse(text: &str) -> Result<Config, String> {
        let j: Json = serde_json::from_str(text).map_err(|e| format!("config: {e}"))?;
        let product = j.get("productId").and_then(|v| v.as_u64()).ok_or("config: no productId")? as u32;
        let account = j.get("account").cloned().unwrap_or(Json::Null);
        let mut owned: Vec<u32> = j.get("owned").and_then(|v| v.as_array()).map(|a| a.iter().filter_map(|v| v.as_u64()).map(|v| v as u32).collect()).unwrap_or_default();
        if !owned.contains(&product) {
            owned.push(product);
        }
        let mut saves = s(&j, "saves").replace('\\', "/");
        if !saves.ends_with('/') {
            saves.push('/');
        }
        let c = Config {
            product,
            exe: s(&j, "exe"),
            args: s(&j, "args"),
            workdir: s(&j, "workdir"),
            user_id: s(&account, "userId"),
            username: s(&account, "username"),
            name_on_platform: s(&account, "nameOnPlatform"),
            email: s(&account, "email"),
            country: s(&account, "country"),
            ticket: s(&j, "ticket"),
            owned,
            app_id: s(&j, "appId"),
            saves,
            language: Some(s(&j, "language")).filter(|l| !l.is_empty()).unwrap_or_else(|| "en-US".into()),
            bridge: s(&j, "bridge"),
            log: s(&j, "log"),
            chunks: j.get("chunks").and_then(|v| v.as_array()).map(|a| a.iter().filter_map(|c| {
                Some((c.get("id")?.as_u64()? as u32, c.get("tags").and_then(|t| t.as_str()).unwrap_or("").to_string()))
            }).collect()).unwrap_or_default(),
            products: j.get("products").and_then(|v| v.as_array()).map(|a| a.iter().filter_map(|p| {
                Some((p.get("id")?.as_u64()? as u32, p.get("type").and_then(|t| t.as_u64()).unwrap_or(0) as u32))
            }).collect()).unwrap_or_default(),
        };
        if c.exe.is_empty() || c.ticket.is_empty() {
            return Err("config: exe and ticket are required".into());
        }
        Ok(c)
    }

    fn root(&self) -> String {
        let mut r = self.workdir.replace('\\', "/");
        if !r.ends_with('/') {
            r.push('/');
        }
        r
    }

    /// The executable the way Connect hands it back: the install root, a slash, then the path under it.
    pub fn reply_exe(&self, requested: &str) -> String {
        let root = self.root();
        let base = root.trim_end_matches('/');
        let exe = requested.replace('\\', "/");
        let rel = if !base.is_empty() && exe.to_lowercase().starts_with(&(base.to_lowercase() + "/")) { &exe[base.len() + 1..] } else { &exe[..] };
        format!("{root}/{rel}")
    }

    /// `game_starter.Downstream` granting the start the starter asked for.
    pub fn game_start(&self, request: &[u8]) -> Vec<u8> {
        let requested = pb::find(request, 10, 4).map(|b| String::from_utf8_lossy(b).into_owned()).unwrap_or_else(|| self.exe.clone());
        let progress = Msg::new()
            .msg(1, Msg::new().uint(1, 1).uint(2, 113).uint(3, 0).uint(4, 1).uint(5, 0))
            .msg(1, Msg::new().uint(1, 3).uint(2, 123).uint(3, 0).uint(4, 0).uint(5, 0));
        let granted = Msg::new()
            .uint(1, 0)
            .uint(2, 0)
            .text(3, &self.reply_exe(&requested))
            .text(4, &self.root())
            .uint(8, 3)
            .msg(9, progress);
        Msg::new().msg(2, Msg::new().msg(1, granted)).0
    }

    /// `Chunks`: every installed chunk, marked downloaded.
    fn chunks(&self) -> Msg {
        let mut m = Msg::new();
        for (id, tags) in &self.chunks {
            m = m.msg(1, Msg::new().uint(1, *id as u64).uint(2, 1).text(3, tags));
        }
        m
    }

    /// `Rsp.initProcessRsp` for the game announcing its process. The overlay is reported off
    /// everywhere: with it on, `uplay_r1.dll` loads `overlay.dll` and fails its startup without it.
    pub fn init_process(&self) -> Vec<u8> {
        Msg::new().msg(13, Msg::new().uint(1, 1).uint(3, 0).uint(4, OVERLAY_NONE).uint(6, 0)).0
    }

    /// `Rsp.initRsp`: the account, its products and the PC ticket. `uplay_r2.dll` copies the
    /// install and storage state into objects it only creates for the subsystems the game asked
    /// for, without checking them, so those parts follow the request when it names subsystems.
    pub fn session(&self, wants: Option<Subsystems>) -> Vec<u8> {
        let account = Msg::new()
            .text(1, &self.user_id)
            .text(2, &self.username)
            .text(3, &self.name_on_platform)
            .text(4, &self.email)
            .text(5, "")
            .text(6, &self.country);
        let mut body = Msg::new().uint(1, 1).msg(2, account).uint(3, 1).uint(4, self.product as u64);
        for id in &self.owned {
            body = body.uint(5, *id as u64);
        }
        body = body
            .msg(6, Msg::new().uint(1, self.product as u64).text(2, ""))
            .uint(7, 0)
            .text(8, &self.ticket);
        if wants.map_or(true, |w| w.install) {
            body = body.msg(14, Msg::new().uint(1, 1).msg(2, self.chunks()).text(3, &self.language));
        }
        body = body
            .msg(11, Msg::new().uint(1, 0))
            .uint(12, 0)
            .msg(15, Msg::new().uint(1, 0).uint(2, OVERLAY_NONE))
            .msg(16, Msg::new().uint(1, 6211).uint(2, 22395).float(3, 1.0));
        if wants.map_or(true, |w| w.storage) {
            body = body.msg(17, Msg::new().text(1, &self.saves));
        }
        body = body
            .bytes(18, b"")
            .msg(19, Msg::new().uint(1, 1).uint(2, 1))
            .msg(21, Msg::new().uint(2, 1));
        if wants.is_some() {
            body = body.bytes(20, b"");
        }
        if !self.app_id.is_empty() {
            body = body.msg(22, Msg::new().text(1, &self.app_id));
        }
        Msg::new().msg(1, body.bytes(23, b"")).0
    }

    /// `Rsp.installerRsp.initInstallerRsp`.
    pub fn installer(&self) -> Vec<u8> {
        Msg::new().msg(10, Msg::new().msg(1, Msg::new().uint(1, 1).msg(2, self.chunks()).text(3, &self.language))).0
    }

    /// `Rsp.productRsp.getProductListRsp`: every owned product as owned, playable and purchased.
    pub fn product_list(&self, request_id: u64) -> Vec<u8> {
        let mut list = Msg::new().uint(1, request_id).uint(2, 1);
        let products: Vec<(u32, u32)> = if self.products.is_empty() { self.owned.iter().map(|id| (*id, 0)).collect() } else { self.products.clone() };
        for (id, kind) in products {
            list = list.msg(3, Msg::new().uint(1, id as u64).uint(2, if kind == 1 { 2 } else { 1 }).uint(3, 1).uint(4, 3).uint(6, 1));
        }
        Msg::new().msg(15, Msg::new().msg(1, list)).0
    }

    pub fn service_hello(&self) -> Vec<u8> {
        Msg::new().msg(3, Msg::new().uint(1, 1).msg(3, Msg::new().text(1, HWSCORE))).0
    }
}

/// Requests answered with only their id and a result: (`Req` field, its request) to (`Rsp` field, its
/// response, `OperationResult`). 1 is ok and 4 not found.
const ACKNOWLEDGED: &[(u32, u32, u32, u32, u64)] = &[
    (8, 1, 5, 1, 1),   // achievements: list (none)
    (8, 2, 5, 2, 4),   // achievements: image
    (8, 3, 5, 3, 1),   // achievements: earn
    (9, 1, 6, 1, 1),   // overlay: show
    (9, 2, 6, 2, 1),   // overlay: shop url
    (9, 4, 6, 3, 1),   // overlay: browser url
    (9, 5, 6, 4, 1),   // overlay: notification
    (9, 6, 6, 5, 1),   // overlay: friend invitation
    (9, 7, 6, 6, 1),   // overlay: friend selection
    (9, 8, 6, 8, 1),   // overlay: micro app
    (10, 1, 7, 1, 4),  // avatar
    (20, 1, 14, 1, 1), // store products (none)
    (23, 3, 15, 5, 1), // track add-on
    (25, 1, 17, 1, 4), // user lookup
    (25, 3, 17, 3, 1), // rich presence
    (25, 4, 17, 4, 1), // played with
];

/// `Rsp.streamingRsp` (20) for `Req.streamingReq` (27): the game is not streamed, so its streaming
/// type is `StreamingType_None` and every other streaming query is not found.
fn streaming(req: &[(u32, Value<'_>)]) -> (Vec<u8>, String) {
    let id = pb::num(req, 1).unwrap_or(0);
    let rsp = if pb::has(req, 3) {
        Msg::new().uint(1, id).uint(2, 1).msg(4, Msg::new().uint(1, 0))
    } else {
        Msg::new().uint(1, id).uint(2, 4)
    };
    (Msg::new().msg(20, rsp).0, format!("streaming id {id} -> not streamed"))
}

/// The id-and-result answer to a request in [ACKNOWLEDGED] or a streaming query, with a name for the log.
pub fn acknowledge(body: &[u8]) -> Option<(Vec<u8>, String)> {
    let f = pb::fields(body)?;
    if let Some(req) = pb::sub(&f, 27).and_then(pb::fields) {
        return Some(streaming(&req));
    }
    for &(req, sub, rsp, rsp_sub, result) in ACKNOWLEDGED {
        let Some(inner) = pb::sub(&f, req).and_then(pb::fields) else { continue };
        let Some(leaf) = pb::sub(&inner, sub).and_then(pb::fields) else { continue };
        let id = pb::num(&leaf, 1).unwrap_or(0);
        let answer = Msg::new().msg(rsp, Msg::new().msg(rsp_sub, Msg::new().uint(1, id).uint(2, result))).0;
        return Some((answer, format!("{req}.{sub} id {id} -> {result}")));
    }
    None
}

/// The subsystems an `InitReq` asks for (fields 4 to 11).
#[derive(Clone, Copy, Debug, Default, PartialEq)]
pub struct Subsystems {
    pub install: bool,
    pub storage: bool,
}

impl Subsystems {
    /// `None` when the request names no subsystem at all.
    pub fn of(init: &[u8]) -> Option<Subsystems> {
        let f = pb::fields(init)?;
        if !(4..=11).any(|n| pb::has(&f, n)) {
            return None;
        }
        let on = |n| pb::num(&f, n).unwrap_or(0) != 0;
        Some(Subsystems { storage: on(7), install: on(8) })
    }
}

pub enum SessionReq {
    Init(Option<Subsystems>),
    Installer,
    ProductList(u64),
    Other,
}

pub fn session_request(body: &[u8]) -> SessionReq {
    match pb::fields(body) {
        Some(f) if pb::sub(&f, 13).and_then(pb::fields).is_some_and(|i| pb::has(&i, 1)) => SessionReq::Installer,
        Some(f) if pb::has(&f, 1) => SessionReq::Init(pb::sub(&f, 1).and_then(Subsystems::of)),
        Some(f) => match pb::sub(&f, 23).and_then(pb::fields).and_then(|p| pb::sub(&p, 1).and_then(pb::fields)) {
            Some(list) => SessionReq::ProductList(pb::num(&list, 1).unwrap_or(0)),
            None => SessionReq::Other,
        },
        _ => SessionReq::Other,
    }
}

/// The product and game process the game announces on the api-process pipe.
pub fn announced(body: &[u8]) -> Option<(u32, u32)> {
    let f = pb::fields(body)?;
    let r = pb::fields(pb::sub(&f, 2)?)?;
    Some((pb::num(&r, 1)? as u32, pb::num(&r, 2)? as u32))
}

pub enum AuxReq<'a> {
    Init(u64),
    Activate(u64, &'a [u8]),
    Other(u64),
}

/// `UplayAuxDll.Req`: an init, or an activation carrying the game's Denuvo request token.
pub fn aux_request(body: &[u8]) -> AuxReq<'_> {
    let Some(f) = pb::fields(body) else { return AuxReq::Other(0) };
    let rid = pb::num(&f, 1).unwrap_or(0);
    let Some(activate) = pb::sub(&f, 3) else {
        return if pb::has(&f, 2) { AuxReq::Init(rid) } else { AuxReq::Other(rid) };
    };
    let token = pb::fields(activate)
        .and_then(|a| pb::sub(&a, 2).and_then(pb::fields))
        .and_then(|t| t.iter().find_map(|(n, v)| match v { Value::Bytes(b) if *n == 2 => Some(*b), _ => None }));
    match token {
        Some(t) => AuxReq::Activate(rid, t),
        None => AuxReq::Other(rid),
    }
}

pub fn aux_init(rid: u64, pid: u32) -> Vec<u8> {
    let mut init = Msg::new().uint(1, 1).uint(2, pid as u64);
    for a in AUX_ADDONS {
        init = init.uint(3, a);
    }
    Msg::new().uint(1, rid).msg(2, init).0
}

pub fn aux_token(rid: u64, game_token: &[u8]) -> Vec<u8> {
    let answer = Msg::new().uint(2, 1).uint(3, 0).bytes(4, game_token).bytes(5, b"");
    Msg::new().uint(1, rid).msg(3, Msg::new().msg(2, answer)).0
}

#[cfg(test)]
mod tests {
    use super::*;

    fn config() -> Config {
        Config::parse(r#"{"productId":109,"exe":"Z:/games/sc/system/splintercell.exe","workdir":"Z:/games/sc","ticket":"T",
            "account":{"userId":"u1","username":"name","nameOnPlatform":"name","email":"e@x","country":"US"},
            "owned":[82],"appId":"a-b","saves":"Z:/saves/u1/109","chunks":[{"id":0,"tags":""},{"id":64,"tags":"custo05"}]}"#).unwrap()
    }

    #[test]
    fn the_owned_list_always_holds_the_game_and_saves_end_with_a_slash() {
        let c = config();
        assert_eq!(c.owned, vec![82, 109]);
        assert_eq!(c.saves, "Z:/saves/u1/109/");
    }

    #[test]
    fn the_start_reply_names_the_exe_under_the_root_with_connects_double_slash() {
        let c = config();
        assert_eq!(c.reply_exe("Z:\\games\\sc\\system\\splintercell.exe"), "Z:/games/sc//system/splintercell.exe");
        let req = Msg::new().msg(1, Msg::new().msg(1, Msg::new().uint(1, 13389).uint(2, 109).text(10, "Z:/games/sc/system/splintercell.exe")));
        let rsp = c.game_start(&req.0);
        let granted = pb::fields(pb::sub(&pb::fields(pb::sub(&pb::fields(&rsp).unwrap(), 2).unwrap()).unwrap(), 1).unwrap()).unwrap();
        assert_eq!(pb::num(&granted, 1), Some(0));
        assert_eq!(pb::sub(&granted, 3), Some(&b"Z:/games/sc//system/splintercell.exe"[..]));
        assert_eq!(pb::sub(&granted, 4), Some(&b"Z:/games/sc/"[..]));
        assert_eq!(pb::num(&granted, 8), Some(3));
    }

    #[test]
    fn the_session_carries_the_ticket_products_and_save_root() {
        let rsp = config().session(None);
        let init = pb::fields(pb::sub(&pb::fields(&rsp).unwrap(), 1).unwrap()).unwrap();
        assert_eq!(pb::num(&init, 1), Some(1));
        assert_eq!(pb::num(&init, 4), Some(109));
        let owned: Vec<u64> = init.iter().filter_map(|(n, v)| match v { Value::Int(x) if *n == 5 => Some(*x), _ => None }).collect();
        assert_eq!(owned, vec![82, 109]);
        assert_eq!(pb::sub(&init, 8), Some(&b"T"[..]));
        let saves = pb::fields(pb::sub(&init, 17).unwrap()).unwrap();
        assert_eq!(pb::sub(&saves, 1), Some(&b"Z:/saves/u1/109/"[..]));
        let account = pb::fields(pb::sub(&init, 2).unwrap()).unwrap();
        assert_eq!(pb::sub(&account, 1), Some(&b"u1"[..]));
        assert_eq!(pb::sub(&account, 5), Some(&b""[..]));
        let install = pb::fields(pb::sub(&init, 14).unwrap()).unwrap();
        assert_eq!(pb::num(&install, 1), Some(1));
        let chunks = pb::fields(pb::sub(&install, 2).unwrap()).unwrap();
        let second = pb::fields(match &chunks[1].1 { Value::Bytes(b) => b, _ => panic!() }).unwrap();
        assert_eq!((pb::num(&second, 1), pb::num(&second, 2), pb::sub(&second, 3)), (Some(64), Some(1), Some(&b"custo05"[..])));
    }

    fn session_fields(rsp: &[u8]) -> Vec<u32> {
        let init = pb::fields(pb::sub(&pb::fields(rsp).unwrap(), 1).unwrap()).unwrap();
        init.iter().map(|(n, _)| *n).collect()
    }

    #[test]
    fn an_r2_session_leaves_out_the_install_and_storage_the_game_did_not_ask_for() {
        let req = Msg::new().msg(1, Msg::new().uint(1, 6145).uint(2, 512).uint(3, 2).uint(4, 1).uint(5, 1).uint(7, 1).uint(8, 0));
        let SessionReq::Init(wants) = session_request(&req.0) else { panic!() };
        assert_eq!(wants, Some(Subsystems { install: false, storage: true }));
        let fields = session_fields(&config().session(wants));
        assert!(!fields.contains(&14));
        assert!(fields.contains(&17));
        assert!(fields.contains(&20));
        let all = session_fields(&config().session(Some(Subsystems { install: true, storage: true })));
        assert!(all.contains(&14) && all.contains(&17));
    }

    #[test]
    fn streaming_queries_say_the_game_is_not_streamed() {
        let (answer, _) = acknowledge(&[0xda, 0x01, 0x06, 0x08, 0x80, 0x80, 0x08, 0x1a, 0x00]).unwrap();
        let rsp = pb::fields(pb::sub(&pb::fields(&answer).unwrap(), 20).unwrap()).unwrap();
        assert_eq!((pb::num(&rsp, 1), pb::num(&rsp, 2)), (Some(0x20000), Some(1)));
        assert_eq!(pb::num(&pb::fields(pb::sub(&rsp, 4).unwrap()).unwrap(), 1), Some(0));
        let (answer, _) = acknowledge(&Msg::new().msg(27, Msg::new().uint(1, 7).bytes(2, b"")).0).unwrap();
        let rsp = pb::fields(pb::sub(&pb::fields(&answer).unwrap(), 20).unwrap()).unwrap();
        assert_eq!((pb::num(&rsp, 1), pb::num(&rsp, 2)), (Some(7), Some(4)));
    }

    #[test]
    fn an_r1_session_without_subsystems_keeps_install_and_storage() {
        let SessionReq::Init(wants) = session_request(&Msg::new().msg(1, Msg::new().uint(1, 109).uint(2, 4242)).0) else { panic!() };
        assert_eq!(wants, None);
        let fields = session_fields(&config().session(wants));
        assert!(fields.contains(&14) && fields.contains(&17));
        assert!(!fields.contains(&20));
    }

    #[test]
    fn requests_are_told_apart() {
        assert!(matches!(session_request(&Msg::new().msg(1, Msg::new().uint(1, 109)).0), SessionReq::Init(None)));
        assert!(matches!(session_request(&Msg::new().msg(13, Msg::new().bytes(1, b"")).0), SessionReq::Installer));
        assert!(matches!(session_request(&[0xba, 0x01, 0x06, 0x0a, 0x04, 0x08, 0x80, 0x80, 0x04]), SessionReq::ProductList(65536)));
        let (answer, _) = acknowledge(&[0x42, 0x08, 0x0a, 0x06, 0x08, 0x80, 0x80, 0x08, 0x10, 0x00]).unwrap();
        let rsp = pb::fields(pb::sub(&pb::fields(pb::sub(&pb::fields(&answer).unwrap(), 5).unwrap()).unwrap(), 1).unwrap()).unwrap();
        assert_eq!((pb::num(&rsp, 1), pb::num(&rsp, 2)), (Some(0x20000), Some(1)));
        assert!(acknowledge(&Msg::new().msg(1, Msg::new().uint(1, 1)).0).is_none());
        assert_eq!(announced(&Msg::new().msg(2, Msg::new().uint(1, 109).uint(2, 4242).uint(5, 1)).0), Some((109, 4242)));
        let activate = Msg::new().uint(1, 2).msg(3, Msg::new().msg(2, Msg::new().bytes(2, b"challenge").uint(3, 0)));
        assert!(matches!(aux_request(&activate.0), AuxReq::Activate(2, b"challenge")));
        assert!(matches!(aux_request(&Msg::new().uint(1, 1).msg(2, Msg::new().uint(1, 109)).0), AuxReq::Init(1)));
    }
}
