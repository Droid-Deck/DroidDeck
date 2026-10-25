use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct NvstRecoveryPolicy {
    pub ref_pic_invalidation: Option<bool>,
    pub stream_recovery_type: Option<u32>,
    pub frames_with_invalid_ref_threshold: Option<u32>,
    pub enable_recovery_mode: Option<bool>,
    pub recovery_mode_trigger_threshold_ms: Option<u32>,
    pub enable_intra_refresh_in_recovery_mode: Option<bool>,
    pub encoder_ltr_feature_setting: Option<u32>,
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    #[test]
    fn recovery_policy_preserves_offered_values_without_defaults() {
        let policy: NvstRecoveryPolicy = serde_json::from_value(json!({
            "refPicInvalidation":true,"streamRecoveryType":2,
            "framesWithInvalidRefThreshold":4,"enableRecoveryMode":false
        }))
        .unwrap();
        assert_eq!(policy.stream_recovery_type, Some(2));
        assert_eq!(policy.ref_pic_invalidation, Some(true));
        assert_eq!(policy.enable_recovery_mode, Some(false));
        assert_eq!(policy.encoder_ltr_feature_setting, None);
        assert_eq!(
            serde_json::from_value::<NvstRecoveryPolicy>(json!({})).unwrap(),
            NvstRecoveryPolicy::default()
        );
    }

    #[test]
    fn recovery_policy_rejects_malformed_handoff_fields() {
        for value in [
            json!({"refPicInvalidation":1}),
            json!({"streamRecoveryType":-1}),
            json!({"framesWithInvalidRefThreshold":4294967296_u64}),
            json!({"unknown":1}),
        ] {
            assert!(serde_json::from_value::<NvstRecoveryPolicy>(value).is_err());
        }
    }
}
