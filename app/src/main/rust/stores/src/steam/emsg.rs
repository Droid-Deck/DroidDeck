#[derive(Clone, Copy, Debug, Default, Eq, Hash, PartialEq)]
#[repr(transparent)]
pub struct EMsg(pub u32);

impl EMsg {
    pub const INVALID: Self = Self(0);
    pub const MULTI: Self = Self(1);
    pub const CHANNEL_ENCRYPT_REQUEST: Self = Self(1303);
    pub const CHANNEL_ENCRYPT_RESPONSE: Self = Self(1304);
    pub const CHANNEL_ENCRYPT_RESULT: Self = Self(1305);
    pub const CLIENT_HELLO: Self = Self(9805);
    pub const CLIENT_LOGON: Self = Self(5514);
    pub const CLIENT_LOGON_RESPONSE: Self = Self(751);
    pub const CLIENT_LOG_OFF: Self = Self(706);
    pub const CLIENT_LOGGED_OFF: Self = Self(757);
    pub const CLIENT_HEART_BEAT: Self = Self(703);
    pub const CLIENT_SESSION_TOKEN: Self = Self(850);
    pub const CLIENT_SERVER_UNAVAILABLE: Self = Self(5500);
    pub const CLIENT_LICENSE_LIST: Self = Self(780);
    /// Free/F2P/demo license grant (verified against JavaSteam `EMsg.java`).
    pub const CLIENT_REQUEST_FREE_LICENSE: Self = Self(5572);
    pub const CLIENT_REQUEST_FREE_LICENSE_RESPONSE: Self = Self(5573);
    pub const CLIENT_PICS_PRODUCT_INFO_REQUEST: Self = Self(8903);
    pub const CLIENT_PICS_PRODUCT_INFO_RESPONSE: Self = Self(8904);
    pub const CLIENT_PICS_ACCESS_TOKEN_REQUEST: Self = Self(8905);
    pub const CLIENT_PICS_ACCESS_TOKEN_RESPONSE: Self = Self(8906);
    pub const CLIENT_GET_DEPOT_DECRYPTION_KEY: Self = Self(5438);
    pub const CLIENT_GET_DEPOT_DECRYPTION_KEY_RESPONSE: Self = Self(5439);
    pub const SERVICE_METHOD: Self = Self(146);
    pub const SERVICE_METHOD_CALL_FROM_CLIENT: Self = Self(151);
    pub const SERVICE_METHOD_RESPONSE: Self = Self(147);
    pub const SERVICE_METHOD_SEND_TO_CLIENT: Self = Self(152);
    pub const SERVICE_METHOD_CALL_FROM_CLIENT_NON_AUTHED: Self = Self(9804);
}

pub const EMSG_PROTO_FLAG: u32 = 0x8000_0000;
pub const EMSG_MASK: u32 = 0x7fff_ffff;

pub const fn has_proto_flag(raw: u32) -> bool {
    (raw & EMSG_PROTO_FLAG) != 0
}

pub const fn strip_proto_flag(raw: u32) -> EMsg {
    EMsg(raw & EMSG_MASK)
}

pub const fn with_proto_flag(msg: EMsg) -> u32 {
    msg.0 | EMSG_PROTO_FLAG
}

#[derive(Clone, Copy, Debug, Default, Eq, Hash, PartialEq)]
#[repr(u32)]
pub enum EUniverse {
    #[default]
    Invalid = 0,
    Public = 1,
    Beta = 2,
    Internal = 3,
    Dev = 4,
    Max = 5,
}

impl EUniverse {
    pub fn from_u32(v: u32) -> Option<Self> {
        match v {
            1 => Some(Self::Public),
            2 => Some(Self::Beta),
            3 => Some(Self::Internal),
            4 => Some(Self::Dev),
            5 => Some(Self::Max),
            _ => None,
        }
    }

    pub fn is_valid_universe(self) -> bool {
        matches!(self, Self::Public | Self::Beta | Self::Internal | Self::Dev)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn universe_values_match_steam_enum() {
        assert_eq!(EUniverse::Invalid as u32, 0);
        assert_eq!(EUniverse::Public as u32, 1);
        assert_eq!(EUniverse::Beta as u32, 2);
        assert_eq!(EUniverse::Internal as u32, 3);
        assert_eq!(EUniverse::Dev as u32, 4);
        assert_eq!(EUniverse::Max as u32, 5);
        assert!(!EUniverse::Max.is_valid_universe());
        assert!(EUniverse::Public.is_valid_universe());
    }

    #[test]
    fn free_license_emsgs_match_steamkit() {
        // JavaSteam EMsg.java: ClientRequestFreeLicense(5572) / ...Response(5573).
        assert_eq!(EMsg::CLIENT_REQUEST_FREE_LICENSE.0, 5572);
        assert_eq!(EMsg::CLIENT_REQUEST_FREE_LICENSE_RESPONSE.0, 5573);
    }
}
