use super::net::{self, Call};

pub const MANIFEST_SEGMENT: &str = "manifest.proto";

pub fn append_path(base: &str, segment: &str) -> String {
    match base.find('?') {
        Some(q) => format!("{}/{segment}{}", &base[..q], &base[q..]),
        None => format!("{base}/{segment}"),
    }
}

pub fn file_url(base: &str, cdn_name: &str) -> String {
    append_path(base, &format!("files/{cdn_name}"))
}

pub fn origin(url: &str) -> String {
    let after_scheme = url.find("://").map(|i| i + 3).unwrap_or(0);
    let end = url[after_scheme..].find(['/', '?', '#']).map(|i| after_scheme + i).unwrap_or(url.len());
    url[..end].to_string()
}

pub fn get(url: &str, access_token: Option<&str>) -> Result<Vec<u8>, String> {
    let mut call = Call::get(url, net::user_agent()).read_timeout(net::BYTES_READ_TIMEOUT);
    if let Some(token) = access_token.filter(|t| !t.is_empty()) {
        call = call.header("x-amzn-token", token);
    }
    let reply = net::send(call)?;
    if !reply.ok() {
        return Err(format!("the CDN answered HTTP {}", reply.status));
    }
    Ok(reply.body)
}

pub fn fetch_manifest(base: &str, access_token: &str) -> Result<Vec<u8>, String> {
    get(&append_path(base, MANIFEST_SEGMENT), Some(access_token))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn segments_go_before_the_query() {
        assert_eq!(
            append_path("https://cdn.example/v1/abc?Expires=1&Signature=x%2By", "manifest.proto"),
            "https://cdn.example/v1/abc/manifest.proto?Expires=1&Signature=x%2By"
        );
        assert_eq!(append_path("https://cdn.example/v1/abc", "manifest.proto"), "https://cdn.example/v1/abc/manifest.proto");
        assert_eq!(append_path("https://h/p?a=1?b=2", "s"), "https://h/p/s?a=1?b=2");
        assert_eq!(file_url("https://h/base?q", "00ff"), "https://h/base/files/00ff?q");
    }

    #[test]
    fn origins() {
        assert_eq!(origin("https://cdn.example:8443/x?y"), "https://cdn.example:8443");
        assert_eq!(origin("https://cdn.example?y"), "https://cdn.example");
    }
}
