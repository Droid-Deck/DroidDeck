//! Reuses the sign-in the Linux Steam client saved ("Remember me"), so the user never signs in
//! twice. Read-only: nothing here writes to the client's files, and the token is never renewed,
//! refreshed or replaced (that would sign the client out).
//!
//! Where it lives, per Steam root:
//! - `config/loginusers.vdf`: the accounts (`<SteamID64> { AccountName, RememberPassword,
//!   MostRecent, Timestamp … }`).
//! - `local.vdf`: `MachineUserConfigStore/Software/Valve/Steam/ConnectCache`, one entry per
//!   remembered account whose key starts with the 8-hex-digit CRC32 of the lowercased account
//!   name (e.g. `9a410a9b` + `1`) and whose value is a hex blob.
//! - `~/.steam/registry.vdf`: `Registry/HKCU/Software/Valve/Steam/AutoLoginUser`.
//!
//! The blob: key = SHA-256(lowercased account name); the first 16 bytes are the IV encrypted with
//! AES-256-ECB; the rest is AES-256-CBC with PKCS#7 padding; the plaintext is the refresh-token
//! JWT. Method as documented by deadlock-api-ingest, `src/gc/auth.rs` (MIT,
//! https://github.com/deadlock-api/deadlock-api-ingest). Keys are matched case-insensitively
//! because clients mix casings.
//!
//! The token stays in memory only, is never logged or written anywhere, and is zeroized on drop.

use crate::steam::crypto::{aes256_cbc_decrypt, aes256_ecb_decrypt_block, sha256};
use crate::steam::vdf::{self, KVNode};
use std::fs;
use std::path::{Path, PathBuf};
use zeroize::{Zeroize, Zeroizing};

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum SigninError {
    /// No Steam root has an account in `loginusers.vdf`.
    NoSavedLogin,
    /// The account exists but the client kept no token for it (Remember me off, or signed out).
    RememberMeOff,
    /// A token blob exists but does not decrypt to this account's Steam JWT.
    DecryptFailed,
}

impl SigninError {
    pub fn code(self) -> &'static str {
        match self {
            SigninError::NoSavedLogin => "no_saved_login",
            SigninError::RememberMeOff => "remember_me_off",
            SigninError::DecryptFailed => "decrypt_failed",
        }
    }
}

/// The saved login of one account. `Debug` keeps the token out of any output.
pub struct SavedLogin {
    pub account: String,
    pub steam_id: u64,
    pub steam_root: PathBuf,
    pub token: Zeroizing<String>,
}

impl std::fmt::Debug for SavedLogin {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("SavedLogin")
            .field("account", &self.account)
            .field("steam_id", &self.steam_id)
            .field("steam_root", &self.steam_root)
            .field("token", &"<redacted>")
            .finish()
    }
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct Account {
    pub steam_id: u64,
    pub account_name: String,
    pub remember_password: Option<bool>,
    pub most_recent: bool,
    pub timestamp: u64,
    pub root_index: usize,
}

pub fn child_ci<'a>(node: &'a KVNode, key: &str) -> Option<&'a KVNode> {
    node.child(key)
}

/// Follows `path` from `root` (whose own name is `path[0]`), matching every key case-insensitively.
pub fn find_path<'a>(root: &'a KVNode, path: &[&str]) -> Option<&'a KVNode> {
    let (first, rest) = path.split_first()?;
    if !root.name.eq_ignore_ascii_case(first) {
        return None;
    }
    rest.iter().try_fold(root, |node, key| child_ci(node, key))
}

fn read_vdf(path: &Path) -> Option<KVNode> {
    let bytes = fs::read(path).ok()?;
    vdf::parse_text(&bytes)
}

pub fn parse_login_users(root: &KVNode, root_index: usize) -> Vec<Account> {
    if !root.name.eq_ignore_ascii_case("users") {
        return Vec::new();
    }
    let mut out = Vec::new();
    for user in &root.children {
        let Ok(steam_id) = user.name.parse::<u64>() else {
            continue;
        };
        let Some(name) = child_ci(user, "AccountName").map(|n| n.as_string("")) else {
            continue;
        };
        if name.is_empty() {
            continue;
        }
        let flag = |key: &str| child_ci(user, key).map(|n| n.as_string("") == "1");
        out.push(Account {
            steam_id,
            account_name: name,
            remember_password: flag("RememberPassword"),
            most_recent: flag("MostRecent").unwrap_or(false),
            timestamp: child_ci(user, "Timestamp")
                .map(|n| n.as_uint(0))
                .unwrap_or(0),
            root_index,
        });
    }
    out
}

pub fn auto_login_user(registry: &KVNode) -> Option<String> {
    let user = find_path(
        registry,
        &[
            "Registry",
            "HKCU",
            "Software",
            "Valve",
            "Steam",
            "AutoLoginUser",
        ],
    )?
    .as_string("");
    (!user.is_empty()).then_some(user)
}

/// `AutoLoginUser` when it names a known account, else the `MostRecent` one, else the newest
/// `Timestamp` (Linux clients may omit `MostRecent`).
pub fn choose_account<'a>(
    accounts: &'a [Account],
    auto_login: Option<&str>,
) -> Option<&'a Account> {
    if let Some(user) = auto_login {
        if let Some(found) = accounts
            .iter()
            .find(|a| a.account_name.eq_ignore_ascii_case(user))
        {
            return Some(found);
        }
    }
    if let Some(found) = accounts.iter().find(|a| a.most_recent) {
        return Some(found);
    }
    accounts.iter().max_by_key(|a| a.timestamp)
}

pub fn connect_cache_key_prefix(account_name: &str) -> String {
    format!(
        "{:08x}",
        crc32fast::hash(account_name.to_lowercase().as_bytes())
    )
}

/// The hex blob for `account_name` in a parsed `local.vdf`.
pub fn connect_cache_blob(local: &KVNode, account_name: &str) -> Option<String> {
    let cache = find_path(
        local,
        &[
            "MachineUserConfigStore",
            "Software",
            "Valve",
            "Steam",
            "ConnectCache",
        ],
    )?;
    let prefix = connect_cache_key_prefix(account_name);
    cache
        .children
        .iter()
        .find(|entry| {
            entry
                .name
                .get(..prefix.len())
                .is_some_and(|p| p.eq_ignore_ascii_case(&prefix))
        })
        .map(|entry| entry.as_string(""))
        .filter(|value| !value.is_empty())
}

fn decode_hex(text: &str) -> Option<Vec<u8>> {
    let text = text.trim();
    if !text.len().is_multiple_of(2) {
        return None;
    }
    let digit = |c: u8| -> Option<u8> {
        match c {
            b'0'..=b'9' => Some(c - b'0'),
            b'a'..=b'f' => Some(c - b'a' + 10),
            b'A'..=b'F' => Some(c - b'A' + 10),
            _ => None,
        }
    };
    text.as_bytes()
        .chunks(2)
        .map(|pair| Some((digit(pair[0])? << 4) | digit(pair[1])?))
        .collect()
}

/// Decrypts a ConnectCache blob (hex) into the refresh-token JWT.
pub fn decrypt_connect_cache(hex_blob: &str, account_name: &str) -> Option<Zeroizing<String>> {
    let blob = Zeroizing::new(decode_hex(hex_blob)?);
    if blob.len() < 32 {
        return None;
    }
    let mut key = sha256(account_name.to_lowercase().as_bytes());
    let mut wrapped = [0u8; 16];
    wrapped.copy_from_slice(&blob[..16]);
    let iv = aes256_ecb_decrypt_block(&key, &wrapped);
    let plain = iv.and_then(|iv| aes256_cbc_decrypt(&key, &iv, &blob[16..]));
    key.zeroize();
    let mut plain = plain?;
    let text = match String::from_utf8(std::mem::take(&mut plain)) {
        Ok(text) => Zeroizing::new(text),
        Err(err) => {
            let mut bytes = err.into_bytes();
            bytes.zeroize();
            return None;
        }
    };
    Some(text)
}

fn base64url_decode(text: &str) -> Option<Vec<u8>> {
    let mut s: String = text
        .chars()
        .map(|c| match c {
            '-' => '+',
            '_' => '/',
            other => other,
        })
        .collect();
    while !s.len().is_multiple_of(4) {
        s.push('=');
    }
    crate::steam::base64::decode(&s)
}

/// The SteamID64 a Steam JWT was issued for (`sub`), if it is a Steam JWT.
pub fn jwt_steam_id(jwt: &str) -> Option<u64> {
    let mut parts = jwt.split('.');
    let (_header, payload, _sig) = (parts.next()?, parts.next()?, parts.next()?);
    if parts.next().is_some() {
        return None;
    }
    let bytes = base64url_decode(payload)?;
    let json: serde_json::Value = serde_json::from_slice(&bytes).ok()?;
    if json.get("iss").and_then(|v| v.as_str()) != Some("steam") {
        return None;
    }
    json.get("sub")?.as_str()?.parse().ok()
}

/// Loads the saved login. `steam_roots` are host paths of Steam roots, searched in order;
/// `registry_vdf` is `~/.steam/registry.vdf` (may be missing).
pub fn load_saved_login(
    steam_roots: &[PathBuf],
    registry_vdf: Option<&Path>,
) -> Result<SavedLogin, SigninError> {
    let mut accounts = Vec::new();
    for (idx, root) in steam_roots.iter().enumerate() {
        if let Some(users) = read_vdf(&root.join("config").join("loginusers.vdf")) {
            accounts.extend(parse_login_users(&users, idx));
        }
    }
    let auto_login = registry_vdf
        .and_then(read_vdf)
        .and_then(|reg| auto_login_user(&reg));
    let account = choose_account(&accounts, auto_login.as_deref())
        .ok_or(SigninError::NoSavedLogin)?
        .clone();
    if account.remember_password == Some(false) {
        return Err(SigninError::RememberMeOff);
    }

    let mut order: Vec<usize> = vec![account.root_index];
    order.extend((0..steam_roots.len()).filter(|&i| i != account.root_index));
    let mut found_blob = false;
    for idx in order {
        let Some(local) = read_vdf(&steam_roots[idx].join("local.vdf")) else {
            continue;
        };
        let Some(mut blob) = connect_cache_blob(&local, &account.account_name) else {
            continue;
        };
        found_blob = true;
        let token = decrypt_connect_cache(&blob, &account.account_name);
        blob.zeroize();
        let Some(token) = token else {
            continue;
        };
        if jwt_steam_id(&token) != Some(account.steam_id) {
            continue;
        }
        return Ok(SavedLogin {
            account: account.account_name.clone(),
            steam_id: account.steam_id,
            steam_root: steam_roots[idx].clone(),
            token,
        });
    }
    Err(if found_blob {
        SigninError::DecryptFailed
    } else {
        SigninError::RememberMeOff
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::steam::crypto::{aes256_cbc_encrypt, aes256_ecb_encrypt_block};

    const ID: u64 = 76561198000000001;

    fn jwt_for(steam_id: u64) -> String {
        let payload = format!(r#"{{"iss":"steam","sub":"{steam_id}","aud":["client","web"]}}"#);
        let b64 = crate::steam::base64::encode(payload.as_bytes())
            .trim_end_matches('=')
            .replace('+', "-")
            .replace('/', "_");
        format!("eyJhbGciOiJFZERTQSJ9.{b64}.c2ln")
    }

    /// Same scheme the client uses: IV wrapped with AES-256-ECB, body AES-256-CBC + PKCS#7.
    fn encrypt_blob(token: &str, account: &str) -> String {
        let key = sha256(account.to_lowercase().as_bytes());
        let iv = [0x5au8; 16];
        let mut out = aes256_ecb_encrypt_block(&key, &iv).unwrap().to_vec();
        out.extend(aes256_cbc_encrypt(&key, &iv, token.as_bytes()).unwrap());
        out.iter().map(|b| format!("{b:02x}")).collect()
    }

    fn temp_dir(name: &str) -> PathBuf {
        let dir = std::env::temp_dir().join(format!(
            "droiddeck_steam_signin_{name}_{}",
            std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .unwrap()
                .as_nanos()
        ));
        fs::create_dir_all(dir.join("config")).unwrap();
        dir
    }

    fn write_root(root: &Path, users: &str, cache_entries: &str) {
        fs::write(root.join("config").join("loginusers.vdf"), users).unwrap();
        fs::write(
            root.join("local.vdf"),
            format!(
                "\"MachineUserConfigStore\"\n{{\n\t\"Software\"\n\t{{\n\t\t\"valve\"\n\t\t{{\n\t\t\t\"steam\"\n\t\t\t{{\n\t\t\t\t\"ConnectCache\"\n\t\t\t\t{{\n{cache_entries}\t\t\t\t}}\n\t\t\t}}\n\t\t}}\n\t}}\n}}\n"
            ),
        )
        .unwrap();
    }

    fn user_block(id: u64, name: &str, extra: &str) -> String {
        format!("\t\"{id}\"\n\t{{\n\t\t\"AccountName\"\t\t\"{name}\"\n{extra}\t}}\n")
    }

    #[test]
    fn crc_prefix_is_zero_padded_lowercase_hex_of_lowercased_name() {
        assert_eq!(
            connect_cache_key_prefix("Ada"),
            format!("{:08x}", crc32fast::hash(b"ada"))
        );
        assert_eq!(connect_cache_key_prefix("x").len(), 8);
    }

    #[test]
    fn encrypts_a_known_token_and_decrypts_it_back() {
        let token = jwt_for(ID);
        let blob = encrypt_blob(&token, "AdaLovelace");
        let clear = decrypt_connect_cache(&blob, "adalovelace").unwrap();
        assert_eq!(clear.as_str(), token);
        assert_eq!(jwt_steam_id(&clear), Some(ID));
        assert!(decrypt_connect_cache(&blob, "someone_else").is_none());
        assert!(decrypt_connect_cache("zz", "adalovelace").is_none());
        assert!(decrypt_connect_cache("00ff", "adalovelace").is_none());
    }

    #[test]
    fn loads_saved_login_with_mixed_case_keys_and_auto_login_user() {
        let root = temp_dir("load");
        let token = jwt_for(ID);
        let other = ID + 1;
        let users = format!(
            "\"users\"\n{{\n{}{}}}\n",
            user_block(other, "second", "\t\t\"MostRecent\"\t\t\"1\"\n"),
            user_block(ID, "AdaLovelace", "\t\t\"RememberPassword\"\t\t\"1\"\n"),
        );
        let entry = format!(
            "\t\t\t\t\t\"{}1\"\t\t\"{}\"\n",
            connect_cache_key_prefix("AdaLovelace").to_uppercase(),
            encrypt_blob(&token, "AdaLovelace")
        );
        write_root(&root, &users, &entry);
        let registry = root.join("registry.vdf");
        fs::write(
            &registry,
            "\"Registry\"\n{\n\t\"HKCU\"\n\t{\n\t\t\"Software\"\n\t\t{\n\t\t\t\"Valve\"\n\t\t\t{\n\t\t\t\t\"Steam\"\n\t\t\t\t{\n\t\t\t\t\t\"AutoLoginUser\"\t\t\"adalovelace\"\n\t\t\t\t}\n\t\t\t}\n\t\t}\n\t}\n}\n",
        )
        .unwrap();

        let login = load_saved_login(std::slice::from_ref(&root), Some(&registry)).unwrap();
        assert_eq!(login.account, "AdaLovelace");
        assert_eq!(login.steam_id, ID);
        assert_eq!(login.token.as_str(), token);
        assert!(!format!("{login:?}").contains(&token));

        // Without AutoLoginUser the MostRecent account wins; it has no token saved.
        assert_eq!(
            load_saved_login(std::slice::from_ref(&root), None).unwrap_err(),
            SigninError::RememberMeOff
        );
        let _ = fs::remove_dir_all(&root);
    }

    #[test]
    fn reports_no_login_remember_me_off_and_decrypt_failures() {
        let empty = temp_dir("empty");
        assert_eq!(
            load_saved_login(std::slice::from_ref(&empty), None).unwrap_err(),
            SigninError::NoSavedLogin
        );

        let off = temp_dir("off");
        write_root(
            &off,
            &format!(
                "\"users\"\n{{\n{}}}\n",
                user_block(ID, "ada", "\t\t\"RememberPassword\"\t\t\"0\"\n")
            ),
            "",
        );
        assert_eq!(
            load_saved_login(std::slice::from_ref(&off), None).unwrap_err(),
            SigninError::RememberMeOff
        );

        let wrong = temp_dir("wrong");
        let entry = format!(
            "\t\t\t\t\t\"{}1\"\t\t\"{}\"\n",
            connect_cache_key_prefix("ada"),
            encrypt_blob(&jwt_for(ID + 5), "ada")
        );
        write_root(
            &wrong,
            &format!("\"users\"\n{{\n{}}}\n", user_block(ID, "ada", "")),
            &entry,
        );
        assert_eq!(
            load_saved_login(std::slice::from_ref(&wrong), None).unwrap_err(),
            SigninError::DecryptFailed
        );
        for dir in [empty, off, wrong] {
            let _ = fs::remove_dir_all(dir);
        }
    }

    #[test]
    fn newest_timestamp_wins_when_most_recent_is_absent() {
        let accounts = vec![
            Account {
                steam_id: 1,
                account_name: "old".into(),
                remember_password: None,
                most_recent: false,
                timestamp: 10,
                root_index: 0,
            },
            Account {
                steam_id: 2,
                account_name: "new".into(),
                remember_password: None,
                most_recent: false,
                timestamp: 20,
                root_index: 0,
            },
        ];
        assert_eq!(choose_account(&accounts, None).unwrap().steam_id, 2);
        assert_eq!(choose_account(&accounts, Some("OLD")).unwrap().steam_id, 1);
        assert_eq!(
            choose_account(&accounts, Some("nobody")).unwrap().steam_id,
            2
        );
        assert!(choose_account(&[], None).is_none());
    }
}
