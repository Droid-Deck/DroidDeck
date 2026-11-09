use serde_json::{json, Value as Json};

use super::demux::Demux;
use super::pb::{self, Msg};

pub struct Owned {
    pub product_id: u32,
    pub space_id: String,
    pub manifest: String,
    pub product_type: u32,
    pub state: u32,
    pub owned: bool,
    pub configuration: String,
    pub app_id: String,
    pub partner: u32,
    pub activation: u32,
    pub locked_by_subscription: bool,
    pub associations: Vec<u32>,
    pub download_id: u32,
}

impl Owned {
    /// Whether Connect lists the product among the session's owned ids: owned games and add-ons that are released.
    pub fn in_session(&self) -> bool {
        self.owned && self.product_type <= 1 && (self.state == 0 || self.state == 3)
    }

    pub fn to_json(&self) -> Json {
        json!({
            "productId": self.product_id, "spaceId": self.space_id, "manifest": self.manifest,
            "productType": self.product_type, "state": self.state, "owned": self.owned,
            "configuration": self.configuration, "appId": self.app_id,
            "partner": self.partner, "activation": self.activation, "lockedBySubscription": self.locked_by_subscription,
            "associations": self.associations, "downloadId": self.download_id,
        })
    }
}

pub struct Ownership {
    conn: u32,
    next: u64,
}

fn owned_game(b: &[u8]) -> Result<Owned, String> {
    let f = pb::fields(b)?;
    Ok(Owned {
        product_id: pb::num(&f, 1).unwrap_or(0) as u32,
        space_id: pb::string(&f, 30).unwrap_or_default(),
        manifest: pb::string(&f, 21).unwrap_or_default(),
        product_type: pb::num(&f, 8).unwrap_or(0) as u32,
        state: pb::num(&f, 13).unwrap_or(0) as u32,
        owned: pb::num(&f, 20).map(|v| v == 1).unwrap_or(true),
        configuration: pb::string(&f, 17).unwrap_or_default(),
        app_id: pb::string(&f, 31).unwrap_or_default(),
        partner: pb::num(&f, 38).unwrap_or(0) as u32,
        activation: pb::num(&f, 36).unwrap_or(0) as u32,
        locked_by_subscription: pb::num(&f, 37) == Some(1),
        associations: f.iter().filter_map(|(n, v)| match v { pb::Value::Varint(x) if *n == 16 => Some(*x as u32), _ => None }).collect(),
        download_id: pb::num(&f, 5).unwrap_or(0) as u32,
    })
}

impl Ownership {
    /// Opens ownership_service and initializes it, which also returns the account's products.
    pub async fn open(d: &mut Demux, ticket: &str, session_id: &str) -> Result<(Self, Vec<Owned>), String> {
        let conn = d.open("ownership_service").await?;
        let init = Msg::new().flag(2, true).uint(3, 7);
        let mut req = Msg::new().uint(1, 1).msg(2, init).text(30, ticket);
        if !session_id.is_empty() {
            req = req.text(31, session_id);
        }
        let raw = d.call(conn, Msg::new().msg(1, req)).await?;
        let rsp = pb::fields(pb::sub(&pb::fields(&raw)?, 1).ok_or("no ownership answer")?)?;
        let init = pb::fields(pb::sub(&rsp, 2).ok_or("no ownership initialize answer")?)?;
        if pb::num(&init, 1) != Some(1) {
            return Err("Ubisoft would not list the account's games".into());
        }
        let mut games = Vec::new();
        if let Some(list) = pb::sub(&init, 2) {
            for g in pb::subs(&pb::fields(list)?, 1) {
                games.push(owned_game(g)?);
            }
        }
        Ok((Ownership { conn, next: 2 }, games))
    }

    /// The latest manifest of each product that has one, for games whose ownership entry leaves it out.
    pub async fn latest_manifests(&mut self, d: &mut Demux, products: &[u32]) -> Result<Vec<(u32, String)>, String> {
        if products.is_empty() {
            return Ok(Vec::new());
        }
        let id = self.next;
        self.next += 1;
        let mut req = Msg::new();
        for p in products {
            req = req.uint(1, *p as u64);
        }
        let raw = d.call(self.conn, Msg::new().msg(1, Msg::new().uint(1, id).msg(10, req))).await?;
        let rsp = pb::fields(pb::sub(&pb::fields(&raw)?, 1).ok_or("no ownership answer")?)?;
        let m = pb::fields(pb::sub(&rsp, 10).ok_or("no manifest answer")?)?;
        if pb::num(&m, 1) != Some(1) {
            return Err("Ubisoft would not list the latest manifests".into());
        }
        let mut out = Vec::new();
        for entry in pb::subs(&m, 2) {
            let e = pb::fields(entry)?;
            if pb::num(&e, 1) != Some(1) {
                continue;
            }
            if let (Some(product), Some(manifest)) = (pb::num(&e, 2), pb::string(&e, 3).filter(|s| !s.is_empty())) {
                out.push((product as u32, manifest));
            }
        }
        Ok(out)
    }

    /// The ticket a game receives in its launcher session (`GetUplayPCTicket`).
    pub async fn pc_ticket(&mut self, d: &mut Demux, product_id: u32, ticket: &str, session_id: &str) -> Result<String, String> {
        let id = self.next;
        self.next += 1;
        let info = Msg::new().uint(1, product_id as u64).uint(2, 0).uint(3, product_id as u64);
        let mut req = Msg::new().uint(1, id).msg(14, info).text(30, ticket);
        if !session_id.is_empty() {
            req = req.text(31, session_id);
        }
        let raw = d.call(self.conn, Msg::new().msg(1, req)).await?;
        let rsp = pb::fields(pb::sub(&pb::fields(&raw)?, 1).ok_or("no ownership answer")?)?;
        let t = pb::fields(pb::sub(&rsp, 13).ok_or("no game ticket answer")?)?;
        if pb::num(&t, 1) != Some(1) {
            return Err(format!("Ubisoft would not issue a game ticket (code {})", pb::num(&t, 3).unwrap_or(0)));
        }
        pb::string(&t, 2).filter(|s| !s.is_empty()).ok_or_else(|| "Ubisoft sent an empty game ticket".into())
    }

    pub async fn token(&mut self, d: &mut Demux, product_id: u32) -> Result<String, String> {
        let id = self.next;
        self.next += 1;
        let raw = d.call(self.conn, Msg::new().msg(1, Msg::new().uint(1, id).msg(28, Msg::new().uint(1, product_id as u64)))).await?;
        let rsp = pb::fields(pb::sub(&pb::fields(&raw)?, 1).ok_or("no ownership answer")?)?;
        let t = pb::fields(pb::sub(&rsp, 27).ok_or("no ownership token answer")?)?;
        if pb::num(&t, 1) != Some(1) {
            return Err("Ubisoft says this account does not own the game".into());
        }
        pb::string(&t, 2).filter(|s| !s.is_empty()).ok_or_else(|| "Ubisoft sent an empty ownership token".into())
    }
}

pub struct Downloads {
    conn: u32,
    next: u64,
}

impl Downloads {
    pub async fn open(d: &mut Demux, ownership_token: &str) -> Result<Self, String> {
        let conn = d.open("download_service").await?;
        let raw = d.call(conn, Msg::new().msg(1, Msg::new().uint(1, 1).msg(2, Msg::new().text(5, ownership_token)))).await?;
        let rsp = pb::fields(pb::sub(&pb::fields(&raw)?, 1).ok_or("no download service answer")?)?;
        let ok = pb::sub(&rsp, 2).map(pb::fields).transpose()?.and_then(|i| pb::num(&i, 1)) == Some(1);
        if !ok {
            return Err("Ubisoft's download service refused the ownership token".into());
        }
        Ok(Downloads { conn, next: 2 })
    }

    /// Signed CDN URLs for paths under the product's download root, in request order; empty where none was given.
    pub async fn sign(&mut self, d: &mut Demux, product_id: u32, paths: &[String]) -> Result<Vec<Vec<String>>, String> {
        let id = self.next;
        self.next += 1;
        let mut request = Msg::new().uint(1, product_id as u64);
        for p in paths {
            request = request.text(2, p);
        }
        let raw = d.call(self.conn, Msg::new().msg(1, Msg::new().uint(1, id).msg(3, Msg::new().msg(1, request)))).await?;
        let rsp = pb::fields(pb::sub(&pb::fields(&raw)?, 1).ok_or("no download service answer")?)?;
        let urls = pb::fields(pb::sub(&rsp, 3).ok_or("no signed URLs")?)?;
        let mut out = Vec::new();
        for r in pb::subs(&urls, 1) {
            let r = pb::fields(r)?;
            if pb::num(&r, 1).unwrap_or(0) != 0 {
                return Err("Ubisoft says this account does not own the game".into());
            }
            for group in pb::subs(&r, 2) {
                out.push(pb::subs(&pb::fields(group)?, 1).iter().map(|u| String::from_utf8_lossy(u).into_owned()).collect());
            }
        }
        if out.len() != paths.len() {
            return Err(format!("Ubisoft signed {} of {} paths", out.len(), paths.len()));
        }
        Ok(out)
    }
}

/// Denuvo's activation for a game the account owns: the game's challenge and the product's ownership
/// token go to `denuvo_service`, which answers with the game token the game's aux DLL waits for.
pub async fn denuvo_token(d: &mut Demux, ownership_token: &str, challenge: &[u8]) -> Result<Vec<u8>, String> {
    let conn = d.open("denuvo_service").await?;
    let req = Msg::new().uint(1, 1).msg(2, Msg::new().text(1, ownership_token).bytes(2, challenge));
    let raw = d.call(conn, Msg::new().msg(1, req)).await?;
    let rsp = pb::fields(pb::sub(&pb::fields(&raw)?, 1).ok_or("no denuvo answer")?)?;
    let result = pb::num(&rsp, 2).unwrap_or(0);
    if result != 0 {
        return Err(format!("Ubisoft's activation service refused the game (result {result})"));
    }
    let t = pb::fields(pb::sub(&rsp, 3).ok_or("no game token answer")?)?;
    pb::sub(&t, 1).filter(|b| !b.is_empty()).map(<[u8]>::to_vec).ok_or_else(|| "Ubisoft sent an empty game token".into())
}
