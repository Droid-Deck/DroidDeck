use aes_gcm::{
    Aes256Gcm, Nonce,
    aead::{Aead, KeyInit, OsRng, rand_core::RngCore},
};
use keyring::Entry;
use serde::{Deserialize, Serialize};
use std::{fs, path::PathBuf};

const SERVICE: &str = "DroidDeckLauncher";
const KEY_NAME: &str = "account-vault-key";

#[derive(Clone, Debug, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct AccountRecord {
    pub store: String,
    #[serde(default)]
    pub display_name: String,
    #[serde(default)]
    pub secret: String,
}

#[derive(Clone, Debug, Default)]
pub struct AccountStatus {
    pub store: String,
    pub display_name: String,
    pub connected: bool,
}

pub fn stores() -> &'static [(&'static str, &'static str, &'static str)] {
    &[
        ("steam", "Steam", "Login integrado de Steam"),
        ("epic", "Epic Games", "Cuenta Epic Games"),
        ("ea", "EA app", "Cuenta EA"),
        ("xbox", "Xbox", "Xbox y PC Game Pass"),
        ("gog", "GOG", "Cuenta GOG"),
        ("ubisoft", "Ubisoft Connect", "Cuenta Ubisoft"),
        ("amazon", "Amazon Games", "Amazon Luna / Prime Gaming"),
        ("battlenet", "Battle.net", "Cliente Battle.net"),
        ("rockstar", "Rockstar Games", "Rockstar Games Launcher"),
        ("itch", "itch.io", "Clave de API de itch.io"),
        ("gamejolt", "Game Jolt", "Cuenta Game Jolt"),
    ]
}

fn valid_store(store: &str) -> bool {
    stores().iter().any(|(id, _, _)| *id == store)
}

fn vault_path() -> Result<PathBuf, String> {
    dirs::data_local_dir()
        .map(|p| p.join("DroidDeckLauncher").join("accounts"))
        .ok_or_else(|| "No se encontró la carpeta de datos local de Windows".to_owned())
}

fn master_key() -> Result<[u8; 32], String> {
    let entry = Entry::new(SERVICE, KEY_NAME).map_err(|e| {
        format!("No se pudo abrir el Administrador de credenciales de Windows: {e}")
    })?;
    match entry.get_password() {
        Ok(encoded) => decode_hex(&encoded)
            .ok_or_else(|| "La clave de la bóveda de cuentas está dañada".to_owned()),
        Err(keyring::Error::NoEntry) => {
            let mut key = [0u8; 32];
            getrandom::fill(&mut key)
                .map_err(|e| format!("No se pudo crear una clave segura: {e}"))?;
            entry
                .set_password(&encode_hex(&key))
                .map_err(|e| format!("No se pudo proteger la bóveda en Windows: {e}"))?;
            Ok(key)
        }
        Err(e) => Err(format!(
            "No se pudo leer la clave protegida de Windows: {e}"
        )),
    }
}

pub fn load(store: &str) -> Result<Option<AccountRecord>, String> {
    if !valid_store(store) {
        return Err("Tienda desconocida".into());
    }
    let path = vault_path()?.join(format!("{store}.bin"));
    let encrypted = match fs::read(path) {
        Ok(bytes) => bytes,
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => return Ok(None),
        Err(error) => {
            return Err(format!(
                "No se pudo leer la sesión cifrada de {store}: {error}"
            ));
        }
    };
    let plaintext = decrypt(&encrypted, &master_key()?)
        .map_err(|e| format!("No se pudo descifrar la sesión de {store}: {e}"))?;
    serde_json::from_slice(&plaintext)
        .map(Some)
        .map_err(|_| format!("La sesión de {store} está dañada. Volvé a conectar la cuenta."))
}

pub fn save(account: &AccountRecord) -> Result<(), String> {
    if !valid_store(&account.store) {
        return Err("Tienda desconocida".into());
    }
    let plaintext = serde_json::to_vec(account).map_err(|e| e.to_string())?;
    let encrypted = encrypt(&plaintext, &master_key()?)?;
    let dir = vault_path()?;
    fs::create_dir_all(&dir).map_err(|e| format!("No se pudo crear la bóveda local: {e}"))?;
    let path = dir.join(format!("{}.bin", account.store));
    let temp = dir.join(format!("{}.bin.tmp", account.store));
    fs::write(&temp, encrypted)
        .map_err(|e| format!("No se pudo guardar la sesión cifrada: {e}"))?;
    fs::rename(&temp, &path).map_err(|e| format!("No se pudo confirmar la sesión cifrada: {e}"))
}

pub fn remove(store: &str) -> Result<(), String> {
    if !valid_store(store) {
        return Err("Tienda desconocida".into());
    }
    let path = vault_path()?.join(format!("{store}.bin"));
    match fs::remove_file(path) {
        Ok(()) => Ok(()),
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => Ok(()),
        Err(error) => Err(format!("No se pudo desconectar {store}: {error}")),
    }
}

pub fn list() -> Vec<AccountStatus> {
    stores()
        .iter()
        .map(|(id, _, _)| match load(id) {
            Ok(Some(record)) => AccountStatus {
                store: (*id).to_owned(),
                display_name: record.display_name,
                connected: true,
            },
            _ => AccountStatus {
                store: (*id).to_owned(),
                display_name: String::new(),
                connected: false,
            },
        })
        .collect()
}

fn encrypt(plaintext: &[u8], key: &[u8; 32]) -> Result<Vec<u8>, String> {
    let cipher = Aes256Gcm::new_from_slice(key).map_err(|e| e.to_string())?;
    let mut nonce = [0u8; 12];
    OsRng.fill_bytes(&mut nonce);
    let mut out = nonce.to_vec();
    out.extend(
        cipher
            .encrypt(Nonce::from_slice(&nonce), plaintext)
            .map_err(|_| "cifrado fallido".to_owned())?,
    );
    Ok(out)
}

fn decrypt(encrypted: &[u8], key: &[u8; 32]) -> Result<Vec<u8>, String> {
    if encrypted.len() < 28 {
        return Err("contenido incompleto".into());
    }
    let cipher = Aes256Gcm::new_from_slice(key).map_err(|e| e.to_string())?;
    cipher
        .decrypt(Nonce::from_slice(&encrypted[..12]), &encrypted[12..])
        .map_err(|_| "clave incorrecta o contenido alterado".into())
}

fn encode_hex(bytes: &[u8]) -> String {
    bytes.iter().map(|b| format!("{b:02x}")).collect()
}

fn decode_hex(value: &str) -> Option<[u8; 32]> {
    if value.len() != 64 {
        return None;
    }
    let mut out = [0u8; 32];
    for (i, byte) in out.iter_mut().enumerate() {
        *byte = u8::from_str_radix(&value[i * 2..i * 2 + 2], 16).ok()?;
    }
    Some(out)
}
