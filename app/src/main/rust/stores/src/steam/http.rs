//! One-shot HTTPS GET for the few non-chunk requests (CM directory, manifests, HTTP-delivered
//! appinfo). Chunk downloads go through the shared fetch core instead.

use std::fs;
use std::time::Duration;

pub const USER_AGENT: &str = crate::fetch_tuning::USER_AGENT;

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
    let body = response
        .bytes()
        .await
        .map_err(|err| format!("http body: {}", err.without_url()))?
        .to_vec();
    Ok(Response {
        status,
        content_length,
        body,
    })
}
