//! Cloud DLSS overrides, mirroring the official GeForce NOW client.
//!
//! The setting is stored as the exact wire object the official client sends in
//! `requestedStreamingFeatures.dlssOverrides`. Every field is an enum value from the
//! client's `DlssSrMode`, `DlssSrModel`, `DlssRrModel`, `DlssFgMode`, and `DlssFgModel`;
//! zero means "use the game's own setting".

use serde_json::{Value, json};

pub const SETTING: &str = "dlssOverrides";

const SR_MODE_CUSTOM: i64 = 6;
const SR_CUSTOM_DEFAULT_SCALE: i64 = 75;
const SR_MINIMUM_SCALE: i64 = 33;
const SR_MAXIMUM_SCALE: i64 = 100;
const SR_MAXIMUM_MODEL: i64 = 14;
const RR_MAXIMUM_MODEL: i64 = 7;
const FG_MAXIMUM_MODEL: i64 = 3;
const FG_MODE_DYNAMIC: i64 = 1;
const FG_MULTIPLIERS: [i64; 4] = [3, 4, 5, 6];

pub fn default_overrides() -> Value {
    json!({"srMode":0,"srModel":0,"srScale":100,"rrModel":0,"fgMode":0,"fgModel":0,"fgMultiplier":0})
}

/// Input resolution percentage the official client pairs with each fixed SR mode.
fn fixed_sr_scale(sr_mode: i64) -> Option<i64> {
    match sr_mode {
        0 | 5 => Some(100),
        3 => Some(67),
        2 => Some(58),
        1 => Some(50),
        4 => Some(33),
        _ => None,
    }
}

fn integer(value: &Value, key: &str) -> Option<i64> {
    value[key]
        .as_f64()
        .filter(|number| number.is_finite() && number.fract() == 0.0)
        .map(|number| number as i64)
}

fn bounded(value: &Value, key: &str, maximum: i64) -> i64 {
    integer(value, key)
        .filter(|number| (0..=maximum).contains(number))
        .unwrap_or(0)
}

/// Clamp every field to a known enum value and apply the official client's coupling:
/// a model left to the game also leaves its mode to the game, fixed SR modes carry their
/// fixed input resolution, and the multiplier only exists for Dynamic frame generation.
pub fn normalize(value: &Value) -> Value {
    let sr_model = bounded(value, "srModel", SR_MAXIMUM_MODEL);
    let sr_mode = if sr_model == 0 {
        0
    } else {
        bounded(value, "srMode", SR_MODE_CUSTOM)
    };
    let sr_scale = fixed_sr_scale(sr_mode).unwrap_or_else(|| {
        integer(value, "srScale")
            .unwrap_or(SR_CUSTOM_DEFAULT_SCALE)
            .clamp(SR_MINIMUM_SCALE, SR_MAXIMUM_SCALE)
    });
    let fg_model = bounded(value, "fgModel", FG_MAXIMUM_MODEL);
    let fg_mode = if fg_model == 0 {
        0
    } else {
        bounded(value, "fgMode", FG_MODE_DYNAMIC)
    };
    let fg_multiplier = if fg_mode == FG_MODE_DYNAMIC {
        integer(value, "fgMultiplier")
            .filter(|multiplier| FG_MULTIPLIERS.contains(multiplier))
            .unwrap_or(FG_MULTIPLIERS[0])
    } else {
        0
    };
    json!({
        "srMode":sr_mode,
        "srModel":sr_model,
        "srScale":sr_scale,
        "rrModel":bounded(value, "rrModel", RR_MAXIMUM_MODEL),
        "fgMode":fg_mode,
        "fgModel":fg_model,
        "fgMultiplier":fg_multiplier
    })
}

/// The `requestedStreamingFeatures.dlssOverrides` object. Like the official client, the
/// multiplier is omitted unless frame generation is Dynamic.
pub fn session_request(settings: &Value) -> Value {
    let mut overrides = normalize(&settings[SETTING]);
    if overrides["fgMode"] != FG_MODE_DYNAMIC {
        overrides
            .as_object_mut()
            .expect("normalized overrides are an object")
            .remove("fgMultiplier");
    }
    overrides
}

/// Which overrides the MES subscription response entitles or upsells, read the way the
/// official client's ecommerce service reads `features.features` and `upsells`.
pub fn entitlements(subscription: &Value) -> Value {
    let features = subscription["features"]["features"]
        .as_array()
        .map(Vec::as_slice)
        .unwrap_or_default();
    let feature = |key: &str| {
        features
            .iter()
            .find(|feature| feature["key"].as_str() == Some(key))
    };
    let entitled = |key: &str| {
        feature(key)
            .and_then(|feature| feature["textValue"].as_str())
            .and_then(|text| serde_json::from_str::<Value>(text).ok())
            .is_some_and(|value| match value {
                Value::Null => false,
                Value::Bool(enabled) => enabled,
                Value::Number(number) => number.as_f64().is_some_and(|number| number != 0.0),
                Value::String(text) => !text.is_empty(),
                Value::Array(items) => !items.is_empty(),
                Value::Object(_) => true,
            })
    };
    let upsell = |key: &str| {
        subscription["upsells"]
            .as_array()
            .into_iter()
            .flatten()
            .any(|upsell| upsell["key"].as_str() == Some(key) && upsell["booleanValue"] == true)
    };
    let capability = |name: &str| {
        json!({
            "entitled":entitled(&format!("SUPPORTED_DLSS_OVERRIDE_{name}")),
            "upsell":upsell(&format!("SUPPORT_DLSS_OVERRIDE_{name}"))
        })
    };
    let multiplier_entitled = |multiplier: i64| {
        let label = format!("{multiplier}X");
        let Some(feature) = feature("SUPPORTED_DLSS_FG_MULTIPLIERS") else {
            return false;
        };
        if let Some(text) = feature["textValue"].as_str() {
            return match serde_json::from_str::<Value>(text) {
                Ok(Value::Array(items)) => items.iter().any(|item| {
                    item["value"].as_str() == Some(label.as_str()) && item["isEntitled"] == true
                }),
                Ok(Value::Bool(enabled)) => enabled,
                _ => false,
            };
        }
        feature["setValue"]
            .as_array()
            .into_iter()
            .flatten()
            .any(|value| value.as_str() == Some(label.as_str()))
    };
    json!({
        "superResolution":capability("SR"),
        "frameGeneration":capability("FG"),
        "rayReconstruction":capability("RR"),
        "frameGenerationMultipliers":FG_MULTIPLIERS
            .into_iter()
            .filter(|multiplier| multiplier_entitled(*multiplier))
            .collect::<Vec<_>>()
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn defaults_leave_every_feature_to_the_game() {
        assert_eq!(normalize(&Value::Null), default_overrides());
        assert_eq!(
            session_request(&json!({})),
            json!({"srMode":0,"srModel":0,"srScale":100,"rrModel":0,"fgMode":0,"fgModel":0})
        );
    }

    #[test]
    fn fixed_sr_modes_carry_the_official_input_resolution() {
        for (mode, scale) in [(0, 100), (1, 50), (2, 58), (3, 67), (4, 33), (5, 100)] {
            let normalized = normalize(&json!({"srModel":1,"srMode":mode,"srScale":90}));
            assert_eq!(normalized["srMode"], mode);
            assert_eq!(normalized["srScale"], scale, "mode {mode}");
        }
    }

    #[test]
    fn custom_sr_scale_is_clamped_and_defaults_to_75() {
        assert_eq!(
            normalize(&json!({"srModel":1,"srMode":6,"srScale":12}))["srScale"],
            33
        );
        assert_eq!(
            normalize(&json!({"srModel":1,"srMode":6,"srScale":140}))["srScale"],
            100
        );
        assert_eq!(
            normalize(&json!({"srModel":1,"srMode":6,"srScale":64.0}))["srScale"],
            64
        );
        assert_eq!(normalize(&json!({"srModel":1,"srMode":6}))["srScale"], 75);
    }

    #[test]
    fn a_model_left_to_the_game_leaves_its_mode_to_the_game() {
        let normalized =
            normalize(&json!({"srModel":0,"srMode":2,"fgModel":0,"fgMode":1,"fgMultiplier":4}));
        assert_eq!(normalized, default_overrides());
    }

    #[test]
    fn multiplier_exists_only_for_dynamic_frame_generation() {
        let dynamic = json!({"dlssOverrides":{"fgModel":1,"fgMode":1,"fgMultiplier":4}});
        assert_eq!(session_request(&dynamic)["fgMultiplier"], 4);
        let invalid = normalize(&json!({"fgModel":1,"fgMode":1,"fgMultiplier":2}));
        assert_eq!(invalid["fgMultiplier"], 3);
        let fixed = normalize(&json!({"fgModel":1,"fgMode":2,"fgMultiplier":4}));
        assert_eq!(fixed["fgMode"], 0);
        assert_eq!(fixed["fgMultiplier"], 0);
        assert!(
            session_request(&json!({"dlssOverrides":fixed}))
                .get("fgMultiplier")
                .is_none()
        );
    }

    #[test]
    fn unknown_enum_values_fall_back_to_the_game() {
        let normalized = normalize(
            &json!({"srModel":15,"srMode":7,"rrModel":8,"fgModel":4,"fgMode":"1","srScale":"58"}),
        );
        assert_eq!(normalized, default_overrides());
    }

    #[test]
    fn the_screenshot_configuration_reaches_the_wire() {
        let settings = json!({"dlssOverrides":{"srMode":2,"srModel":1,"srScale":10,"rrModel":1,"fgMode":1,"fgModel":1,"fgMultiplier":3}});
        assert_eq!(
            session_request(&settings),
            json!({"srMode":2,"srModel":1,"srScale":58,"rrModel":1,"fgMode":1,"fgModel":1,"fgMultiplier":3})
        );
    }

    #[test]
    fn entitlements_follow_the_official_feature_and_upsell_readers() {
        let subscription = json!({
            "features":{"features":[
                {"key":"SUPPORTED_DLSS_OVERRIDE_SR","textValue":"true"},
                {"key":"SUPPORTED_DLSS_OVERRIDE_FG","textValue":"[\"DYNAMIC\"]"},
                {"key":"SUPPORTED_DLSS_OVERRIDE_RR","textValue":"false"},
                {"key":"SUPPORTED_DLSS_FG_MULTIPLIERS","textValue":"[{\"value\":\"3X\",\"isEntitled\":true},{\"value\":\"4X\",\"isEntitled\":true},{\"value\":\"5X\",\"isEntitled\":false}]"}
            ]},
            "upsells":[
                {"key":"SUPPORT_DLSS_OVERRIDE_RR","booleanValue":true},
                {"key":"SUPPORT_DLSS_OVERRIDE_FG","booleanValue":false}
            ]
        });
        assert_eq!(
            entitlements(&subscription),
            json!({
                "superResolution":{"entitled":true,"upsell":false},
                "frameGeneration":{"entitled":true,"upsell":false},
                "rayReconstruction":{"entitled":false,"upsell":true},
                "frameGenerationMultipliers":[3,4]
            })
        );
    }

    #[test]
    fn multiplier_set_values_and_missing_features_are_read() {
        let subscription = json!({"features":{"features":[
            {"key":"SUPPORTED_DLSS_FG_MULTIPLIERS","setValue":["3X","4X","5X","6X"]}
        ]}});
        let read = entitlements(&subscription);
        assert_eq!(read["frameGenerationMultipliers"], json!([3, 4, 5, 6]));
        assert_eq!(
            read["superResolution"],
            json!({"entitled":false,"upsell":false})
        );
        assert_eq!(
            entitlements(&json!({}))["frameGenerationMultipliers"],
            json!([])
        );
    }
}
