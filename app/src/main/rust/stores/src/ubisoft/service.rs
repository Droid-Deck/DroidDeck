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
            "configuration": self.configuration,
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
