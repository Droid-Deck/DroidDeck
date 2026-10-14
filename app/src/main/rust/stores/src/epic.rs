//! Derived from GameNative's Epic manager (itself derived from Legendary); both are GPL-3.0 and this crate carries the same licence (see Cargo.toml).

pub mod assemble;
pub mod adaptive;
pub mod chunk;
pub mod driver;
pub mod jni;
pub mod manifest;
pub mod plan;
pub mod stream;

pub const USER_AGENT: &str =
    "UELauncher/11.0.1-14907503+++Portal+Release-Live Windows/10.0.19041.1.256.64bit";

pub const CHUNK_CACHE_DIR: &str = ".chunks";

pub const JAVA_POOL_THREADS: usize = 8;

pub const LOG_TAG: &str = "EpicNative";
