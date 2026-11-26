//! One-shot HTTPS GET for the few non-chunk requests (CM directory, manifests, HTTP-delivered
//! appinfo). Chunk downloads go through the shared fetch core instead.

use std::fs;
use std::time::Duration;

pub const USER_AGENT: &str = crate::fetch_tuning::USER_AGENT;
/// Largest body kept from a successful response (the biggest depot manifests are tens of MB).
pub const MAX_BODY_BYTES: usize = 256 << 20;
/// Largest body kept from any other status; callers only use it for diagnostics.
const MAX_ERROR_BODY_BYTES: usize = 64 << 10;

pub struct Response {
    pub status: u16,
    pub content_length: Option<u64>,
    pub body: Vec<u8>,
}

pub fn get(url: &str, timeout: Duration, ca_bundle_path: &str) -> Result<Response, String> {
    let runtime = tokio::runtime::Builder::new_current_thread()
        .enable_all()
        .build()
        .map_err(|err| format!("runtime: {err}"))?;
    runtime.block_on(get_async(url, timeout, ca_bundle_path))
}

async fn get_async(url: &str, timeout: Duration, ca_bundle_path: &str) -> Result<Response, String> {
    let mut builder = reqwest::Client::builder()
        .user_agent(USER_AGENT)
        .connect_timeout(crate::fetch_tuning::connect_timeout())
        .timeout(timeout);
    if !ca_bundle_path.is_empty() {
        let pem = fs::read(ca_bundle_path).map_err(|err| format!("read CA bundle: {err}"))?;
        let certs = reqwest::Certificate::from_pem_bundle(&pem)
            .map_err(|err| format!("parse CA bundle: {err}"))?;
        for cert in certs {
            builder = builder.add_root_certificate(cert);
        }
    }
    let client = builder
        .build()
        .map_err(|err| format!("http client: {err}"))?;
    let response = client
        .get(url)
        .send()
        .await
        .map_err(|err| format!("http get: {}", err.without_url()))?;
    let status = response.status().as_u16();
    let content_length = response.content_length();
    let limit = if status == 200 {
        MAX_BODY_BYTES
    } else {
        MAX_ERROR_BODY_BYTES
    };
    if status == 200 && content_length.is_some_and(|len| len > limit as u64) {
        return Err(format!("http body: larger than {limit} bytes"));
    }
    let mut response = response;
    let mut body = Vec::new();
    while let Some(chunk) = response
        .chunk()
        .await
        .map_err(|err| format!("http body: {}", err.without_url()))?
    {
        if body.len() + chunk.len() > limit {
            if status == 200 {
                return Err(format!("http body: larger than {limit} bytes"));
            }
            body.extend_from_slice(&chunk[..limit - body.len()]);
            break;
        }
        body.extend_from_slice(&chunk);
    }
    Ok(Response {
        status,
        content_length,
        body,
    })
}
