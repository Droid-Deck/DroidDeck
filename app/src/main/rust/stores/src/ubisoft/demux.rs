use std::sync::Arc;
use std::time::Duration;

use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::TcpStream;
use tokio_rustls::client::TlsStream;
use tokio_rustls::rustls::{self, pki_types::ServerName, ClientConfig, RootCertStore};
use tokio_rustls::TlsConnector;

use super::pb::{self, Msg};

const HOST: &str = "dmx.upc.ubisoft.com";
const PORT: u16 = 443;
const CLIENT_ID: &str = "uplay_pc";
/// The Connect build number Demux expects first on a socket; older ones get ClientOutdatedPush and a closed socket.
pub const CLIENT_VERSION: u32 = 13247;
const READ_TIMEOUT: Duration = Duration::from_secs(20);
const MAX_FRAME: usize = 64 << 20;

pub struct Demux {
    tls: TlsStream<TcpStream>,
    next_id: u32,
}

impl Demux {
    pub async fn connect(ticket: &str) -> Result<Self, String> {
        let mut roots = RootCertStore::empty();
        roots.extend(webpki_roots::TLS_SERVER_ROOTS.iter().cloned());
        let provider = Arc::new(rustls::crypto::ring::default_provider());
        let config = ClientConfig::builder_with_provider(provider)
            .with_safe_default_protocol_versions()
            .map_err(|e| format!("TLS setup: {e}"))?
            .with_root_certificates(roots)
            .with_no_client_auth();
        let tcp = tokio::time::timeout(READ_TIMEOUT, TcpStream::connect((HOST, PORT)))
            .await
            .map_err(|_| "Ubisoft's download service did not answer".to_string())?
            .map_err(|e| format!("Could not reach Ubisoft's download service: {e}"))?;
        let name = ServerName::try_from(HOST).map_err(|e| e.to_string())?.to_owned();
        let tls = TlsConnector::from(Arc::new(config))
            .connect(name, tcp)
            .await
            .map_err(|e| format!("TLS with Ubisoft's download service failed: {e}"))?;
        let mut d = Demux { tls, next_id: 1 };
        d.send(Msg::new().msg(2, Msg::new().msg(4, Msg::new().uint(1, CLIENT_VERSION as u64)))).await?;
        let id = d.id();
        let auth = Msg::new().uint(1, id as u64).msg(
            2,
            Msg::new()
                .msg(1, Msg::new().text(3, ticket))
                .text(2, CLIENT_ID)
                .flag(4, false),
        );
        d.send(Msg::new().msg(1, auth)).await?;
        let rsp = d.response(id).await?;
        let a = pb::fields(pb::sub(&pb::fields(&rsp)?, 2).ok_or("Ubisoft did not answer the sign-in")?)?;
        if pb::num(&a, 1) != Some(1) {
            let expired = pb::num(&a, 2) == Some(1);
            return Err(if expired { "The Ubisoft sign-in expired".into() } else { "Ubisoft refused the sign-in".into() });
        }
        Ok(d)
    }

    fn id(&mut self) -> u32 {
        let id = self.next_id;
        self.next_id += 1;
        id
    }

    async fn send(&mut self, up: Msg) -> Result<(), String> {
        let len = (up.0.len() as u32).to_be_bytes();
        self.tls.write_all(&len).await.map_err(|e| e.to_string())?;
        self.tls.write_all(&up.0).await.map_err(|e| e.to_string())?;
        self.tls.flush().await.map_err(|e| e.to_string())
    }

    async fn frame(&mut self) -> Result<Vec<u8>, String> {
        let read = async {
            let mut len = [0u8; 4];
            self.tls.read_exact(&mut len).await.map_err(|e| format!("Ubisoft closed the connection: {e}"))?;
            let n = u32::from_be_bytes(len) as usize;
            if n > MAX_FRAME {
                return Err(format!("Ubisoft sent a {n}-byte frame"));
            }
            let mut body = vec![0u8; n];
            self.tls.read_exact(&mut body).await.map_err(|e| e.to_string())?;
            Ok(body)
        };
        tokio::time::timeout(READ_TIMEOUT, read).await.map_err(|_| "Ubisoft's download service stopped answering".to_string())?
    }

    fn check_push(down: &[(u32, pb::Value<'_>)]) -> Result<(), String> {
        if let Some(push) = pb::sub(down, 2) {
            let p = pb::fields(push)?;
            if pb::first(&p, 5).is_some() {
                return Err("Ubisoft says this client version is outdated".into());
            }
        }
        Ok(())
    }

    async fn response(&mut self, id: u32) -> Result<Vec<u8>, String> {
        for _ in 0..64 {
            let frame = self.frame().await?;
            let down = pb::fields(&frame)?;
            Self::check_push(&down)?;
            if let Some(rsp) = pb::sub(&down, 1) {
                if pb::num(&pb::fields(rsp)?, 1) == Some(id as u64) {
                    return Ok(rsp.to_vec());
                }
            }
        }
        Err("Ubisoft never answered the request".into())
    }

    pub async fn open(&mut self, service: &str) -> Result<u32, String> {
        let id = self.id();
        self.send(Msg::new().msg(1, Msg::new().uint(1, id as u64).msg(3, Msg::new().text(2, service)))).await?;
        let rsp = self.response(id).await?;
        let o = pb::fields(pb::sub(&pb::fields(&rsp)?, 3).ok_or("no connection answer")?)?;
        if pb::num(&o, 2) != Some(1) {
            return Err(format!("Ubisoft refused the {service} connection"));
        }
        pb::num(&o, 1).map(|c| c as u32).ok_or_else(|| "no connection id".into())
    }

    /// One service request on a connection; the payload is length-prefixed inside the DataMessage both ways.
    pub async fn call(&mut self, connection: u32, payload: Msg) -> Result<Vec<u8>, String> {
        let mut framed = (payload.0.len() as u32).to_be_bytes().to_vec();
        framed.extend_from_slice(&payload.0);
        self.send(Msg::new().msg(2, Msg::new().msg(1, Msg::new().uint(1, connection as u64).bytes(2, &framed)))).await?;
        for _ in 0..64 {
            let frame = self.frame().await?;
            let down = pb::fields(&frame)?;
            Self::check_push(&down)?;
            let Some(push) = pb::sub(&down, 2) else { continue };
            let p = pb::fields(push)?;
            if let Some(closed) = pb::sub(&p, 2) {
                let c = pb::fields(closed)?;
                if pb::num(&c, 1) == Some(connection as u64) {
                    return Err(format!("Ubisoft closed the service connection (code {})", pb::num(&c, 2).unwrap_or(0)));
                }
            }
            let Some(data) = pb::sub(&p, 1) else { continue };
            let d = pb::fields(data)?;
            if pb::num(&d, 1) != Some(connection as u64) {
                continue;
            }
            let body = pb::sub(&d, 2).unwrap_or_default();
            return Ok(if body.len() >= 4 { body[4..].to_vec() } else { body.to_vec() });
        }
        Err("Ubisoft never answered the service request".into())
    }
}
