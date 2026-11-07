//! SteamPipe CDN: URL formation, manifest + HTTP-delivered appinfo fetches, and the zip/gzip
//! helpers they need. Chunk bodies are fetched by the shared fetch core (`crate::fetch_core`).

use crate::steam::pb::ccontentserverdirectory::CContentServerDirectoryServerInfo;
use flate2::read::{DeflateDecoder, GzDecoder};
use std::io::Read;
use std::time::Duration;

/// Distinct CDN hosts a depot run spreads over. Each chunk carries one URL per host, so this also
/// bounds the per-chunk URL memory.
pub const MAX_CDN_HOSTS: usize = 10;

pub const MAX_MANIFEST_FETCH_ATTEMPTS: usize = 5;

#[derive(Clone, Debug, Default, Eq, PartialEq)]
pub struct CdnManifestResult {
    pub raw_manifest: Vec<u8>,
    pub error: String,
    pub http_status: i32,
}

impl CdnManifestResult {
    pub fn ok(&self) -> bool {
        self.error.is_empty() && !self.raw_manifest.is_empty()
    }
}

pub fn default_timeout() -> Duration {
    Duration::from_secs(30)
}

pub fn build_manifest_url(
    server: &CContentServerDirectoryServerInfo,
    depot_id: u32,
    manifest_id: u64,
    request_code: u64,
) -> Result<String, String> {
    let host = preferred_host(server).ok_or_else(|| "cdn server has no host".to_string())?;
    let mut url = format!(
        "{}://{}:{}/depot/{}/manifest/{}/5",
        if server.use_https() { "https" } else { "http" },
        host,
        server.port(),
        depot_id,
        manifest_id
    );
    if request_code != 0 {
        url.push('/');
        url.push_str(&request_code.to_string());
    }
    Ok(url)
}

pub fn build_chunk_url(
    server: &CContentServerDirectoryServerInfo,
    depot_id: u32,
    chunk_sha: &[u8],
) -> Result<String, String> {
    if chunk_sha.is_empty() {
        return Err("empty chunk sha".to_string());
    }
    let host = preferred_host(server).ok_or_else(|| "cdn server has no host".to_string())?;
    Ok(format!(
        "{}://{}:{}/depot/{}/chunk/{}",
        if server.use_https() { "https" } else { "http" },
        host,
        server.port(),
        depot_id,
        hex_encode(chunk_sha)
    ))
}

/// The per-host key the fetch core caps concurrency on (vhost when present, as the URL uses it).
pub fn host_key(server: &CContentServerDirectoryServerInfo) -> String {
    preferred_host(server).unwrap_or_default().to_string()
}

/// Usable servers (not China-only, with a host), de-duplicated by host key, in directory order,
/// at most [`MAX_CDN_HOSTS`].
pub fn usable_servers(
    servers: &[CContentServerDirectoryServerInfo],
) -> Vec<CContentServerDirectoryServerInfo> {
    let mut out: Vec<CContentServerDirectoryServerInfo> = Vec::new();
    for server in servers {
        if server.steam_china_only || server.host.is_empty() {
            continue;
        }
        let key = host_key(server);
        if out.iter().any(|s| host_key(s) == key) {
            continue;
        }
        out.push(server.clone());
        if out.len() >= MAX_CDN_HOSTS {
            break;
        }
    }
    out
}

pub fn fetch_manifest(
    server: &CContentServerDirectoryServerInfo,
    depot_id: u32,
    manifest_id: u64,
    request_code: u64,
    ca_bundle_path: &str,
    timeout: Duration,
) -> CdnManifestResult {
    let url = match build_manifest_url(server, depot_id, manifest_id, request_code) {
        Ok(url) => url,
        Err(error) => {
            return CdnManifestResult {
                error,
                ..Default::default()
            }
        }
    };
    match crate::steam::http::get(&url, timeout, ca_bundle_path) {
        Ok(response) => validate_manifest_response(
            response.status as i32,
            response.body,
            response.content_length,
        ),
        Err(error) => CdnManifestResult {
            error,
            ..Default::default()
        },
    }
}

/// Tries the servers in order (wrapping) with the shared back-off, up to
/// [`MAX_MANIFEST_FETCH_ATTEMPTS`] attempts.
pub fn fetch_manifest_with_retry(
    servers: &[CContentServerDirectoryServerInfo],
    depot_id: u32,
    manifest_id: u64,
    request_code: u64,
    ca_bundle_path: &str,
    cancel: &std::sync::atomic::AtomicBool,
) -> CdnManifestResult {
    if servers.is_empty() {
        return CdnManifestResult {
            error: "no CDN servers available".to_string(),
            ..Default::default()
        };
    }
    let mut last = CdnManifestResult::default();
    for attempt in 0..MAX_MANIFEST_FETCH_ATTEMPTS {
        if cancel.load(std::sync::atomic::Ordering::Relaxed) {
            return CdnManifestResult {
                error: "cancelled".to_string(),
                ..Default::default()
            };
        }
        if attempt > 0 {
            std::thread::sleep(Duration::from_millis(
                crate::fetch_tuning::retry_backoff_millis(attempt as u32),
            ));
        }
        let server = &servers[attempt % servers.len()];
        last = fetch_manifest(
            server,
            depot_id,
            manifest_id,
            request_code,
            ca_bundle_path,
            default_timeout(),
        );
        if last.ok() {
            return last;
        }
    }
    last
}

/// HTTP-delivered PICS appinfo URL (large appinfo the CM did not inline).
pub fn build_appinfo_url(http_host: &str, app_id: u32, sha: &[u8]) -> String {
    format!(
        "http://{}/appinfo/{}/sha/{}.txt.gz",
        http_host,
        app_id,
        hex_encode(sha)
    )
}

/// Fetch + gunzip an HTTP-delivered appinfo blob (same VDF as an inline `buffer`).
pub fn fetch_appinfo(
    http_host: &str,
    app_id: u32,
    sha: &[u8],
    ca_bundle_path: &str,
    timeout: Duration,
) -> Option<Vec<u8>> {
    if http_host.is_empty() || sha.is_empty() {
        return None;
    }
    let url = build_appinfo_url(http_host, app_id, sha);
    let response = crate::steam::http::get(&url, timeout, ca_bundle_path).ok()?;
    if response.status != 200 || response.body.is_empty() {
        return None;
    }
    Some(maybe_gunzip(response.body))
}

pub fn validate_manifest_response(
    http_status: i32,
    body: Vec<u8>,
    content_length: Option<u64>,
) -> CdnManifestResult {
    if http_status != 200 {
        return CdnManifestResult {
            http_status,
            error: format!("HTTP status {http_status}"),
            ..Default::default()
        };
    }
    if let Some(expected) = content_length {
        if body.len() as u64 != expected {
            return CdnManifestResult {
                http_status,
                error: "manifest body truncated (length mismatch)".to_string(),
                ..Default::default()
            };
        }
    }
    let Some(raw_manifest) = unzip_first_entry(&body) else {
        return CdnManifestResult {
            http_status,
            error: "manifest unzip failed".to_string(),
            ..Default::default()
        };
    };
    CdnManifestResult {
        raw_manifest,
        error: String::new(),
        http_status,
    }
}

pub fn unzip_first_entry(zip: &[u8]) -> Option<Vec<u8>> {
    if zip.len() < 30 || read_u32(zip, 0)? != 0x0403_4b50 {
        return None;
    }
    let flags = read_u16(zip, 6)?;
    let method = read_u16(zip, 8)?;
    let comp_size = read_u32(zip, 18)? as usize;
    let uncomp_size = read_u32(zip, 22)? as usize;
    let name_len = read_u16(zip, 26)? as usize;
    let extra_len = read_u16(zip, 28)? as usize;

    if (flags & 0x08) != 0 {
        return None;
    }

    let data_off = 30usize.checked_add(name_len)?.checked_add(extra_len)?;
    if data_off.checked_add(comp_size)? > zip.len() {
        return None;
    }
    let data = &zip[data_off..data_off + comp_size];

    match method {
        0 => Some(data.to_vec()),
        8 => {
            let mut out = Vec::with_capacity(uncomp_size);
            DeflateDecoder::new(data).read_to_end(&mut out).ok()?;
            Some(out)
        }
        _ => None,
    }
}

/// Gunzip; pass through already-inflated bytes (edge caches may decompress).
pub fn maybe_gunzip(body: Vec<u8>) -> Vec<u8> {
    if body.len() >= 2 && body[0] == 0x1f && body[1] == 0x8b {
        let mut out = Vec::new();
        if GzDecoder::new(&body[..]).read_to_end(&mut out).is_ok() && !out.is_empty() {
            return out;
        }
    }
    body
}

pub fn hex_encode(bytes: &[u8]) -> String {
    crate::gog::to_hex(bytes)
}

fn preferred_host(server: &CContentServerDirectoryServerInfo) -> Option<&str> {
    if !server.vhost.is_empty() {
        Some(&server.vhost)
    } else if !server.host.is_empty() {
        Some(&server.host)
    } else {
        None
    }
}

fn read_u16(buf: &[u8], off: usize) -> Option<u16> {
    Some(u16::from_le_bytes(buf.get(off..off + 2)?.try_into().ok()?))
}

fn read_u32(buf: &[u8], off: usize) -> Option<u32> {
    Some(u32::from_le_bytes(buf.get(off..off + 4)?.try_into().ok()?))
}

#[cfg(test)]
mod tests {
    use super::*;
    use flate2::{write::DeflateEncoder, Compression};
    use std::io::Write;

    #[test]
    fn extracts_stored_zip_entry() {
        let zip = zip_with_entry(0, b"manifest bytes");
        assert_eq!(unzip_first_entry(&zip), Some(b"manifest bytes".to_vec()));
    }

    #[test]
    fn extracts_deflated_zip_entry() {
        let mut encoder = DeflateEncoder::new(Vec::new(), Compression::default());
        encoder.write_all(b"manifest bytes").unwrap();
        let deflated = encoder.finish().unwrap();
        let zip = zip_with_compressed_entry(8, &deflated, b"manifest bytes".len() as u32);
        assert_eq!(unzip_first_entry(&zip), Some(b"manifest bytes".to_vec()));
    }

    #[test]
    fn rejects_data_descriptor_zip_entry() {
        let mut zip = zip_with_entry(0, b"x");
        zip[6] = 0x08;
        assert_eq!(unzip_first_entry(&zip), None);
    }

    #[test]
    fn hex_encodes_lowercase() {
        assert_eq!(hex_encode(&[0, 1, 0xab, 0xff]), "0001abff");
    }

    #[test]
    fn builds_http_appinfo_url() {
        assert_eq!(
            build_appinfo_url("cache1.steamcontent.com", 601150, &[0xab, 0xcd, 0x01]),
            "http://cache1.steamcontent.com/appinfo/601150/sha/abcd01.txt.gz"
        );
    }

    #[test]
    fn gunzips_gzip_and_passes_through_plain_vdf() {
        use flate2::write::GzEncoder;
        let mut encoder = GzEncoder::new(Vec::new(), Compression::default());
        encoder.write_all(b"\"appinfo\"{}").unwrap();
        let gz = encoder.finish().unwrap();
        assert_eq!(maybe_gunzip(gz), b"\"appinfo\"{}");
        assert_eq!(maybe_gunzip(b"\"appinfo\"{}".to_vec()), b"\"appinfo\"{}");
    }

    #[test]
    fn builds_manifest_and_chunk_urls() {
        let server = CContentServerDirectoryServerInfo {
            host: "edge.steamcontent.com".into(),
            vhost: "cdn.example".into(),
            https_support: "mandatory".into(),
            ..Default::default()
        };
        assert_eq!(
            build_manifest_url(&server, 100, 200, 300).unwrap(),
            "https://cdn.example:443/depot/100/manifest/200/5/300"
        );
        assert_eq!(
            build_chunk_url(&server, 100, &[0xab, 0xcd]).unwrap(),
            "https://cdn.example:443/depot/100/chunk/abcd"
        );
        let plain = CContentServerDirectoryServerInfo {
            host: "cache1.example".into(),
            ..Default::default()
        };
        assert_eq!(
            build_manifest_url(&plain, 1, 2, 0).unwrap(),
            "http://cache1.example:80/depot/1/manifest/2/5"
        );
    }

    #[test]
    fn usable_servers_drop_china_empty_and_duplicate_hosts() {
        let server = |host: &str, vhost: &str, china: bool| CContentServerDirectoryServerInfo {
            host: host.into(),
            vhost: vhost.into(),
            steam_china_only: china,
            ..Default::default()
        };
        let list = usable_servers(&[
            server("a", "", false),
            server("b", "", true),
            server("", "", false),
            server("c", "a", false),
            server("d", "", false),
        ]);
        let hosts: Vec<_> = list.iter().map(host_key).collect();
        assert_eq!(hosts, vec!["a", "d"]);
        let many: Vec<_> = (0..20)
            .map(|i| server(&format!("h{i}"), "", false))
            .collect();
        assert_eq!(usable_servers(&many).len(), MAX_CDN_HOSTS);
    }

    #[test]
    fn validates_http_status_lengths_and_manifest_zip() {
        let zip = zip_with_entry(0, b"manifest bytes");
        let ok = validate_manifest_response(200, zip.clone(), Some(zip.len() as u64));
        assert!(ok.ok());
        assert_eq!(ok.raw_manifest, b"manifest bytes");
        let short = validate_manifest_response(200, zip, Some(999));
        assert_eq!(short.error, "manifest body truncated (length mismatch)");
        assert_eq!(
            validate_manifest_response(404, vec![], None).error,
            "HTTP status 404"
        );
    }

    fn zip_with_entry(method: u16, data: &[u8]) -> Vec<u8> {
        zip_with_compressed_entry(method, data, data.len() as u32)
    }

    fn zip_with_compressed_entry(method: u16, data: &[u8], uncomp_size: u32) -> Vec<u8> {
        let mut out = Vec::new();
        out.extend_from_slice(&0x0403_4b50u32.to_le_bytes());
        out.extend_from_slice(&20u16.to_le_bytes());
        out.extend_from_slice(&0u16.to_le_bytes());
        out.extend_from_slice(&method.to_le_bytes());
        out.extend_from_slice(&0u16.to_le_bytes());
        out.extend_from_slice(&0u16.to_le_bytes());
        out.extend_from_slice(&0u32.to_le_bytes());
        out.extend_from_slice(&(data.len() as u32).to_le_bytes());
        out.extend_from_slice(&uncomp_size.to_le_bytes());
        out.extend_from_slice(&1u16.to_le_bytes());
        out.extend_from_slice(&0u16.to_le_bytes());
        out.extend_from_slice(b"x");
        out.extend_from_slice(data);
        out
    }
}
