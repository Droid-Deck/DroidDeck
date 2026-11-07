//! Steam store: signs in with the Linux Steam client's saved login, downloads a game straight into
//! the client's library folder and registers it the way the client does. Derived from WinNative's
//! wn-steam-client (GPL-3.0-or-later); see `NOTICE.md` in this directory.

pub mod base64;
pub mod cdn;
pub mod cm_client;
pub mod cm_runtime;
pub mod cm_server;
pub mod cm_server_list;
pub mod cmsg_protobuf_header;
pub mod content_manifest;
pub mod crypto;
pub mod depot_chunk;
pub mod depot_config;
pub mod depot_downloader;
pub mod depot_writer;
pub mod emsg;
pub mod encrypted_channel;
pub mod handshake_messages;
pub mod heartbeat;
pub mod http;
pub mod install;
pub mod jni;
pub mod job_manager;
pub mod key_dictionary;
pub mod kv;
pub mod library;
pub mod pb;
pub mod plan;
pub mod proto_envelope;
pub mod proto_wire;
pub mod register;
pub mod session;
pub mod signin;
pub mod steam_directory;
pub mod transport;
pub mod vdf;
pub mod wire_format;
pub mod ws_connection;

pub const LOG_TAG: &str = "DroidDeckSteam";

#[cfg(target_os = "android")]
#[link(name = "log")]
unsafe extern "C" {
    fn __android_log_write(prio: i32, tag: *const i8, text: *const i8) -> i32;
}

/// One line to logcat under [`LOG_TAG`]. Never pass a token or any other secret.
pub fn log(message: &str) {
    #[cfg(target_os = "android")]
    {
        use std::ffi::CString;
        let Ok(tag) = CString::new(LOG_TAG) else {
            return;
        };
        let Ok(text) = CString::new(message.replace('\0', " ")) else {
            return;
        };
        unsafe {
            let _ = __android_log_write(4, tag.as_ptr().cast(), text.as_ptr().cast());
        }
    }
    #[cfg(not(target_os = "android"))]
    {
        let _ = message;
    }
}
