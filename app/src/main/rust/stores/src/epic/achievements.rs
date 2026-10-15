use std::collections::HashMap;

use serde_json::{json, Value};

use super::net::{self, Body, Request};

const GRAPHQL_URL: &str = "https://launcher.store.epicgames.com/graphql";

const DEFINITIONS_QUERY: &str = "query Achievement($sandboxId: String!, $locale: String!) { \
Achievement { productAchievementsRecordBySandbox(sandboxId: $sandboxId, locale: $locale) { \
achievements { achievement { name hidden unlockedDisplayName lockedDisplayName \
unlockedDescription lockedDescription unlockedIconLink lockedIconLink XP } } } } }";

const PROGRESS_QUERY: &str = "query PlayerAchievement($epicAccountId: String!, $sandboxId: String!) { \
PlayerAchievement { playerAchievementGameRecordsBySandbox(epicAccountId: $epicAccountId, sandboxId: $sandboxId) { \
records { playerAchievements { playerAchievement { achievementName unlocked unlockDate XP } } } } } }";

fn ask(access_token: &str, query: &str, variables: Value) -> Option<Value> {
    let body = json!({ "query": query, "variables": variables }).to_string();
    let reply = net::send(Request::post(GRAPHQL_URL, Body::Json(body))
        .agent(net::EPIC_LAUNCHER_USER_AGENT)
        .bearer(access_token)).ok()?;
    if !reply.ok() {
        return None;
    }
    reply.json()
}

pub fn unlocks_of(answer: &Value) -> HashMap<String, i64> {
    let mut out = HashMap::new();
    let records = answer
        .pointer("/data/PlayerAchievement/playerAchievementGameRecordsBySandbox/records")
        .and_then(|r| r.as_array());
    for record in records.into_iter().flatten() {
        let list = record.get("playerAchievements").and_then(|p| p.as_array());
        for entry in list.into_iter().flatten() {
            let Some(a) = entry.get("playerAchievement") else { continue };
            if a.get("unlocked").and_then(|u| u.as_bool()) != Some(true) {
                continue;
            }
            let name = net::str_of(a, "achievementName");
            if !name.is_empty() {
                let at = super::timefmt::iso_seconds(net::str_of(a, "unlockDate")).unwrap_or(0);
                out.insert(name.to_string(), at);
            }
        }
    }
    out
}

pub fn merge(definitions: &Value, unlocks: &HashMap<String, i64>) -> Vec<Value> {
    let list = definitions
        .pointer("/data/Achievement/productAchievementsRecordBySandbox/achievements")
        .and_then(|a| a.as_array());
    let mut out = Vec::new();
    for entry in list.into_iter().flatten() {
        let Some(a) = entry.get("achievement") else { continue };
        let id = net::str_of(a, "name");
        if id.is_empty() {
            continue;
        }
        let unlocked_at = unlocks.get(id).copied();
        let unlocked = unlocked_at.is_some();
        let pick = |when_unlocked: &str, when_locked: &str| {
            let first = net::str_of(a, if unlocked { when_unlocked } else { when_locked });
            if !first.is_empty() { first.to_string() } else { net::str_of(a, when_unlocked).to_string() }
        };
        let name = Some(pick("unlockedDisplayName", "lockedDisplayName")).filter(|n| !n.is_empty()).unwrap_or_else(|| id.to_string());
        out.push(json!({
            "id": id,
            "name": name,
            "description": pick("unlockedDescription", "lockedDescription"),
            "hidden": a.get("hidden").and_then(|h| h.as_bool()).unwrap_or(false),
            "unlocked": unlocked,
            "unlockedAt": unlocked_at.unwrap_or(0),
            "icon": net::str_of(a, "unlockedIconLink"),
            "iconLocked": net::str_of(a, "lockedIconLink"),
        }));
    }
    out
}

pub fn fetch(access_token: &str, namespace: &str, account_id: &str, locale: &str) -> Option<Vec<Value>> {
    let definitions = ask(access_token, DEFINITIONS_QUERY, json!({ "sandboxId": namespace, "locale": locale }))?;
    let unlocks = if account_id.is_empty() {
        HashMap::new()
    } else {
        let progress = ask(
            access_token,
            PROGRESS_QUERY,
            json!({ "epicAccountId": account_id, "sandboxId": namespace }),
        )?;
        unlocks_of(&progress)
    };
    Some(merge(&definitions, &unlocks))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn definitions_and_unlocks_merge() {
        let defs = json!({"data": {"Achievement": {"productAchievementsRecordBySandbox": {"achievements": [
            {"achievement": {"name": "a1", "hidden": false, "unlockedDisplayName": "Won", "lockedDisplayName": "???",
                             "unlockedDescription": "You won", "lockedDescription": "", "unlockedIconLink": "u", "lockedIconLink": "l"}},
            {"achievement": {"name": "a2", "hidden": true, "unlockedDisplayName": "Secret", "lockedDisplayName": "",
                             "unlockedDescription": "", "lockedDescription": "", "unlockedIconLink": "", "lockedIconLink": ""}},
            {"achievement": {"name": ""}}
        ]}}}});
        let progress = json!({"data": {"PlayerAchievement": {"playerAchievementGameRecordsBySandbox": {"records": [
            {"playerAchievements": [
                {"playerAchievement": {"achievementName": "a1", "unlocked": true, "unlockDate": "2024-05-01T12:00:00.000+0200"}},
                {"playerAchievement": {"achievementName": "a2", "unlocked": false, "unlockDate": ""}}
            ]}
        ]}}}});
        let list = merge(&defs, &unlocks_of(&progress));
        assert_eq!(list.len(), 2);
        assert_eq!(list[0]["unlocked"], true);
        assert_eq!(list[0]["name"], "Won");
        assert_eq!(list[0]["unlockedAt"], 1_714_557_600);
        assert_eq!(list[1]["unlocked"], false);
        assert_eq!(list[1]["name"], "Secret");
        assert_eq!(list[1]["hidden"], true);
        assert!(merge(&json!({"data": null}), &HashMap::new()).is_empty());
    }
}
