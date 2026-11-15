// SPDX-License-Identifier: GPL-3.0-or-later
//! Ranged reads of EA's build archives.

use std::time::Duration;

use super::zip::{self, Directory, Entry};

const CONNECT_TIMEOUT: Duration = Duration::from_secs(30);
const READ_TIMEOUT: Duration = Duration::from_secs(60);
const MAX_DIRECTORY: u64 = 512 * 1024 * 1024;

pub fn user_agent() -> String {
    crate::amazon::net::user_agent()
}

pub struct Ranged {
    pub body: Vec<u8>,
    pub start: u64,
    pub total: u64,
}

fn content_range(value: &str) -> Option<(u64, u64)> {
    let rest = value.trim().strip_prefix("bytes")?.trim_start();
    let (span, total) = rest.split_once('/')?;
    let start = span.split_once('-')?.0.trim().parse().ok()?;
    Some((start, total.trim().parse().ok()?))
}

pub fn get_range(url: &str, range: &str) -> Result<Ranged, String> {
    let runtime = tokio::runtime::Builder::new_current_thread()
        .enable_all()
        .build()
        .map_err(|e| format!("runtime: {e}"))?;
    runtime.block_on(async {
        let client = reqwest::Client::builder()
            .user_agent(user_agent())
            .connect_timeout(CONNECT_TIMEOUT)
            .read_timeout(READ_TIMEOUT)
            .build()
            .map_err(|e| format!("http client: {}", e.without_url()))?;
        let response = client
            .get(url)
            .header(reqwest::header::RANGE, format!("bytes={range}"))
            .send()
            .await
            .map_err(|e| format!("request failed: {}", e.without_url()))?;
        let status = response.status().as_u16();
        if status == 403 || status == 404 {
            return Err(format!("the download link was refused ({status})"));
        }
        if status != 206 {
            return Err(format!("the server did not answer a ranged read ({status})"));
        }
        let (start, total) = response
            .headers()
            .get(reqwest::header::CONTENT_RANGE)
            .and_then(|v| v.to_str().ok())
            .and_then(content_range)
            .ok_or("the server gave no content range")?;
        let body = response.bytes().await.map_err(|e| format!("answer could not be read: {}", e.without_url()))?.to_vec();
        Ok(Ranged { body, start, total })
    })
}

pub struct Archive {
    pub total: u64,
    pub directory: Directory,
    pub entries: Vec<Entry>,
}

pub fn read_archive(url: &str) -> Result<Archive, String> {
    let tail = get_range(url, &format!("-{}", zip::TAIL_LEN))?;
    let directory = zip::locate(&tail.body, tail.start)?;
    if directory.size > MAX_DIRECTORY || directory.offset + directory.size > tail.total {
        return Err("The archive directory is out of bounds".into());
    }
    let bytes = if directory.offset >= tail.start {
        let at = (directory.offset - tail.start) as usize;
        let end = at + directory.size as usize;
        tail.body.get(at..end).ok_or("The archive directory is cut short")?.to_vec()
    } else {
        let end = directory.offset + directory.size - 1;
        get_range(url, &format!("{}-{end}", directory.offset))?.body
    };
    let entries = zip::parse(&bytes, directory.entries)?;
    Ok(Archive { total: tail.total, directory, entries })
}

#[cfg(test)]
mod tests {
    use super::content_range;

    #[test]
    fn reads_content_range() {
        assert_eq!(content_range("bytes 100-199/5000"), Some((100, 5000)));
        assert_eq!(content_range("bytes */5000"), None);
    }
}
