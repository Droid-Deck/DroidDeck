//! Which depots to install: what the Steam client picks for a Windows game run through Proton
//! (DroidDeck maps Proton globally), from the app's PICS appinfo.
//!
//! - `config.oslist` empty or containing `windows`;
//! - `config.osarch` empty or `64`; depots marked `32` only when the set has no `64` depot;
//! - `config.language` empty or the client language (english when the game has no depot for the
//!   requested language but has english ones);
//! - `config.lowviolence` depots skipped;
//! - DLC depots (`dlcappid`) only when that DLC is owned;
//! - `depotfromapp` / `sharedinstall` depots are shared depots of another app (e.g. 228980
//!   Steamworks Common Redistributables) and go into that app's folder;
//! - the public branch's manifest gid, and the app's public `buildid`.

use crate::steam::vdf::KVNode;
use serde_json::{json, Value};
use std::collections::HashSet;

#[derive(Clone, Debug, Default, Eq, PartialEq)]
pub struct PlannedDepot {
    pub depot_id: u32,
    pub manifest_id: u64,
    pub size: u64,
    pub download: u64,
    pub dlc_app_id: u32,
}

#[derive(Clone, Debug, Default, Eq, PartialEq)]
pub struct SharedRef {
    pub depot_id: u32,
    pub from_app_id: u32,
}

#[derive(Clone, Debug, Default, Eq, PartialEq)]
pub struct SharedDepot {
    pub depot_id: u32,
    pub from_app_id: u32,
    pub manifest_id: u64,
    pub size: u64,
    pub download: u64,
    pub from_name: String,
    pub from_install_dir: String,
    pub from_build_id: u64,
}

#[derive(Clone, Debug, Default, Eq, PartialEq)]
pub struct AppPlan {
    pub app_id: u32,
    pub name: String,
    pub install_dir: String,
    pub build_id: u64,
    /// The language written to the app manifest (the requested one, or english, see above).
    pub language: String,
    pub depots: Vec<PlannedDepot>,
    pub shared_refs: Vec<SharedRef>,
    pub shared: Vec<SharedDepot>,
}

impl AppPlan {
    pub fn install_bytes(&self) -> u64 {
        self.depots.iter().map(|d| d.size).sum::<u64>()
            + self.shared.iter().map(|d| d.size).sum::<u64>()
    }

    pub fn download_bytes(&self) -> u64 {
        self.depots.iter().map(|d| d.download).sum::<u64>()
            + self.shared.iter().map(|d| d.download).sum::<u64>()
    }

    pub fn to_json(&self) -> Value {
        json!({
            "ok": true,
            "appId": self.app_id,
            "name": self.name,
            "installDir": self.install_dir,
            "buildId": self.build_id,
            "language": self.language,
            "downloadBytes": self.download_bytes(),
            "installBytes": self.install_bytes(),
            "depots": self.depots.iter().map(|d| json!({
                "depotId": d.depot_id,
                "manifestId": d.manifest_id.to_string(),
                "size": d.size,
                "dlcAppId": d.dlc_app_id,
            })).collect::<Vec<_>>(),
            "shared": self.shared.iter().map(|d| json!({
                "depotId": d.depot_id,
                "fromAppId": d.from_app_id,
                "manifestId": d.manifest_id.to_string(),
                "size": d.size,
            })).collect::<Vec<_>>(),
        })
    }
}

/// `appinfo` as PICS delivers it: either the `appinfo` node itself or a root holding it.
pub fn appinfo_root(root: &KVNode) -> &KVNode {
    if root.name.eq_ignore_ascii_case("appinfo") {
        root
    } else {
        root.child("appinfo").unwrap_or(root)
    }
}

fn str_at(node: &KVNode, path: &[&str]) -> String {
    let mut cur = node;
    for key in path {
        match cur.child(key) {
            Some(next) => cur = next,
            None => return String::new(),
        }
    }
    if cur.is_object() {
        String::new()
    } else {
        cur.as_string("")
    }
}

fn uint_at(node: &KVNode, path: &[&str]) -> u64 {
    str_at(node, path).trim().parse().unwrap_or(0)
}

pub fn app_name(appinfo: &KVNode) -> String {
    str_at(appinfo, &["common", "name"])
}

pub fn app_type(appinfo: &KVNode) -> String {
    str_at(appinfo, &["common", "type"])
}

pub fn app_install_dir(appinfo: &KVNode) -> String {
    str_at(appinfo, &["config", "installdir"])
}

pub fn public_build_id(appinfo: &KVNode) -> u64 {
    uint_at(appinfo, &["depots", "branches", "public", "buildid"])
}

/// Free to play / free app, as appinfo marks it.
pub fn is_free(appinfo: &KVNode) -> bool {
    str_at(appinfo, &["common", "freetogame"]) == "1"
        || str_at(appinfo, &["extended", "isfreeapp"]) == "1"
}

/// Public manifest gid, size and download size of one depot entry (new layout
/// `manifests.public { gid size download }`, or the old `manifests.public "<gid>"` + `maxsize`).
pub fn public_manifest(depot: &KVNode) -> Option<(u64, u64, u64)> {
    let public = depot.child("manifests")?.child("public")?;
    if public.is_object() {
        let gid = uint_at(public, &["gid"]);
        if gid == 0 {
            return None;
        }
        let size = uint_at(public, &["size"]);
        let download = uint_at(public, &["download"]);
        Some((gid, size, download))
    } else {
        let gid = public.as_uint(0);
        if gid == 0 {
            return None;
        }
        Some((gid, uint_at(depot, &["maxsize"]), 0))
    }
}

fn os_ok(config: Option<&KVNode>) -> bool {
    let oslist = config.map(|c| str_at(c, &["oslist"])).unwrap_or_default();
    oslist.trim().is_empty()
        || oslist
            .split(',')
            .any(|os| os.trim().eq_ignore_ascii_case("windows"))
}

fn arch_of(config: Option<&KVNode>) -> String {
    config
        .map(|c| str_at(c, &["osarch"]).trim().to_string())
        .unwrap_or_default()
}

fn language_of(config: Option<&KVNode>) -> String {
    config
        .map(|c| str_at(c, &["language"]).trim().to_ascii_lowercase())
        .unwrap_or_default()
}

fn low_violence(config: Option<&KVNode>) -> bool {
    config.is_some_and(|c| str_at(c, &["lowviolence"]) == "1")
}

struct Candidate<'a> {
    depot_id: u32,
    node: &'a KVNode,
    arch: String,
    language: String,
}

/// Applies the osarch preference and the language choice to a candidate set.
fn narrow<'a>(mut set: Vec<Candidate<'a>>, language: &str) -> Vec<Candidate<'a>> {
    if set.iter().any(|c| c.arch == "64") {
        set.retain(|c| c.arch != "32");
    }
    set.retain(|c| c.arch.is_empty() || c.arch == "64" || c.arch == "32");
    set.retain(|c| c.language.is_empty() || c.language == language);
    set
}

/// The language to install: the requested one when the game has a depot for it (or has no
/// language-tagged depots at all), else english when the game has english depots.
pub fn effective_language(appinfo: &KVNode, requested: &str) -> String {
    let requested = requested.trim().to_ascii_lowercase();
    let requested = if requested.is_empty() {
        "english".to_string()
    } else {
        requested
    };
    let Some(depots) = appinfo.child("depots") else {
        return requested;
    };
    let tags: HashSet<String> = depots
        .children
        .iter()
        .filter(|d| d.name.parse::<u32>().is_ok())
        .map(|d| language_of(d.child("config")))
        .filter(|l| !l.is_empty())
        .collect();
    if tags.is_empty() || tags.contains(&requested) || !tags.contains("english") {
        requested
    } else {
        "english".to_string()
    }
}

/// Plans the app's own depots and lists the shared depots it references (resolved later with
/// [`resolve_shared`] from the owning apps' appinfo).
pub fn plan_app(
    app_id: u32,
    appinfo: &KVNode,
    owned_apps: &HashSet<u32>,
    language: &str,
) -> Result<AppPlan, String> {
    let appinfo = appinfo_root(appinfo);
    let language = effective_language(appinfo, language);
    let mut plan = AppPlan {
        app_id,
        name: app_name(appinfo),
        install_dir: app_install_dir(appinfo),
        build_id: public_build_id(appinfo),
        language: language.clone(),
        ..Default::default()
    };
    if plan.install_dir.is_empty() {
        return Err("appinfo has no installdir".to_string());
    }
    let Some(depots) = appinfo.child("depots") else {
        return Err("appinfo has no depots".to_string());
    };

    let mut own: Vec<Candidate> = Vec::new();
    let mut shared: Vec<Candidate> = Vec::new();
    for depot in &depots.children {
        let Ok(depot_id) = depot.name.parse::<u32>() else {
            continue;
        };
        if !depot.is_object() {
            continue;
        }
        let config = depot.child("config");
        if !os_ok(config) || low_violence(config) {
            continue;
        }
        let dlc = uint_at(depot, &["dlcappid"]) as u32;
        if dlc != 0 && !owned_apps.contains(&dlc) {
            continue;
        }
        let candidate = Candidate {
            depot_id,
            node: depot,
            arch: arch_of(config),
            language: language_of(config),
        };
        let from_app = uint_at(depot, &["depotfromapp"]) as u32;
        let shared_install = str_at(depot, &["sharedinstall"]) == "1";
        if from_app != 0 || shared_install {
            shared.push(candidate);
        } else {
            own.push(candidate);
        }
    }

    for candidate in narrow(own, &language) {
        let Some((manifest_id, size, download)) = public_manifest(candidate.node) else {
            continue;
        };
        plan.depots.push(PlannedDepot {
            depot_id: candidate.depot_id,
            manifest_id,
            size,
            download,
            dlc_app_id: uint_at(candidate.node, &["dlcappid"]) as u32,
        });
    }
    for candidate in narrow(shared, &language) {
        let from_app = uint_at(candidate.node, &["depotfromapp"]) as u32;
        if from_app == 0 || from_app == app_id {
            continue;
        }
        plan.shared_refs.push(SharedRef {
            depot_id: candidate.depot_id,
            from_app_id: from_app,
        });
    }
    if plan.depots.is_empty() {
        return Err("no installable Windows depot".to_string());
    }
    Ok(plan)
}

/// Fills [`AppPlan::shared`] from the owning apps' appinfo (`lookup(from_app_id)`); a shared
/// depot without a public manifest there is left out.
pub fn resolve_shared(plan: &mut AppPlan, lookup: &dyn Fn(u32) -> Option<KVNode>) {
    plan.shared.clear();
    let mut cache: Vec<(u32, Option<KVNode>)> = Vec::new();
    for shared_ref in plan.shared_refs.clone() {
        let pos = match cache
            .iter()
            .position(|(id, _)| *id == shared_ref.from_app_id)
        {
            Some(pos) => pos,
            None => {
                cache.push((shared_ref.from_app_id, lookup(shared_ref.from_app_id)));
                cache.len() - 1
            }
        };
        let Some(info) = cache[pos].1.as_ref() else {
            continue;
        };
        let info = appinfo_root(info);
        let Some(depot) = info
            .child("depots")
            .and_then(|d| d.child(&shared_ref.depot_id.to_string()))
        else {
            continue;
        };
        let Some((manifest_id, size, download)) = public_manifest(depot) else {
            continue;
        };
        let install_dir = app_install_dir(info);
        if install_dir.is_empty() {
            continue;
        }
        plan.shared.push(SharedDepot {
            depot_id: shared_ref.depot_id,
            from_app_id: shared_ref.from_app_id,
            manifest_id,
            size,
            download,
            from_name: app_name(info),
            from_install_dir: install_dir,
            from_build_id: public_build_id(info),
        });
    }
}

#[cfg(test)]
pub mod tests {
    use super::*;

    /// Shaped like Brawlhalla's (291550) appinfo. Depot 291551/228988 gids and sizes and the
    /// build ids are the captured install's; `download` values and depots 291552-291556 are made
    /// up for the selection rules.
    pub const BRAWLHALLA: &str = r#""appinfo"
{
	"appid"		"291550"
	"common"
	{
		"name"		"Brawlhalla"
		"type"		"Game"
		"freetogame"		"1"
	}
	"config"
	{
		"installdir"		"Brawlhalla"
	}
	"depots"
	{
		"228988"
		{
			"config"
			{
				"oslist"		"windows"
			}
			"depotfromapp"		"228980"
			"sharedinstall"		"1"
		}
		"291551"
		{
			"config"
			{
				"oslist"		"windows"
			}
			"manifests"
			{
				"public"
				{
					"gid"		"4224536525702420323"
					"size"		"1565156559"
					"download"		"1521899507"
				}
			}
		}
		"291552"
		{
			"config"
			{
				"oslist"		"macos"
			}
			"manifests"
			{
				"public"
				{
					"gid"		"11"
					"size"		"5"
				}
			}
		}
		"291553"
		{
			"config"
			{
				"oslist"		"windows"
				"osarch"		"32"
			}
			"manifests"
			{
				"public"
				{
					"gid"		"12"
					"size"		"5"
				}
			}
		}
		"291554"
		{
			"config"
			{
				"oslist"		"windows"
				"osarch"		"64"
				"language"		"german"
			}
			"manifests"
			{
				"public"
				{
					"gid"		"13"
					"size"		"7"
				}
			}
		}
		"291555"
		{
			"config"
			{
				"lowviolence"		"1"
			}
			"manifests"
			{
				"public"		"14"
			}
			"maxsize"		"9"
		}
		"291556"
		{
			"dlcappid"		"291560"
			"manifests"
			{
				"public"
				{
					"gid"		"15"
					"size"		"3"
				}
			}
		}
		"branches"
		{
			"public"
			{
				"buildid"		"26465982"
			}
		}
	}
}
"#;

    pub const REDIST: &str = r#""appinfo"
{
	"appid"		"228980"
	"common"
	{
		"name"		"Steamworks Common Redistributables"
		"type"		"Config"
	}
	"config"
	{
		"installdir"		"Steamworks Shared"
	}
	"depots"
	{
		"228988"
		{
			"config"
			{
				"oslist"		"windows"
			}
			"manifests"
			{
				"public"
				{
					"gid"		"6645201662696499616"
					"size"		"29212173"
					"download"		"29212496"
				}
			}
		}
		"branches"
		{
			"public"
			{
				"buildid"		"24098430"
			}
		}
	}
}
"#;

    pub fn parse(text: &str) -> KVNode {
        crate::steam::vdf::parse_text(text.as_bytes()).unwrap()
    }

    #[test]
    fn picks_windows_64bit_language_and_shared_depots_like_the_client() {
        let info = parse(BRAWLHALLA);
        let mut plan = plan_app(291550, &info, &HashSet::new(), "english").unwrap();
        assert_eq!(plan.name, "Brawlhalla");
        assert_eq!(plan.install_dir, "Brawlhalla");
        assert_eq!(plan.build_id, 26465982);
        assert_eq!(plan.language, "english");
        let ids: Vec<u32> = plan.depots.iter().map(|d| d.depot_id).collect();
        // 291552 macOS, 291553 32-bit (a 64-bit depot exists), 291554 german, 291555 low
        // violence and 291556 unowned DLC are all left out.
        assert_eq!(ids, vec![291551]);
        assert_eq!(plan.depots[0].manifest_id, 4224536525702420323);
        assert_eq!(plan.depots[0].size, 1565156559);
        assert_eq!(
            plan.shared_refs,
            vec![SharedRef {
                depot_id: 228988,
                from_app_id: 228980
            }]
        );
        resolve_shared(&mut plan, &|app| (app == 228980).then(|| parse(REDIST)));
        assert_eq!(plan.shared.len(), 1);
        let shared = &plan.shared[0];
        assert_eq!(shared.manifest_id, 6645201662696499616);
        assert_eq!(shared.size, 29212173);
        assert_eq!(shared.from_install_dir, "Steamworks Shared");
        assert_eq!(shared.from_build_id, 24098430);
        // BytesToStage of the captured install = game depot + shared depot.
        assert_eq!(plan.install_bytes(), 1594368732);
        let json = plan.to_json();
        assert_eq!(json["depots"][0]["manifestId"], "4224536525702420323");
        assert_eq!(json["shared"][0]["fromAppId"], 228980);
    }

    #[test]
    fn owned_dlc_german_and_32bit_fallback() {
        let info = parse(BRAWLHALLA);
        let owned: HashSet<u32> = [291560].into_iter().collect();
        let plan = plan_app(291550, &info, &owned, "german").unwrap();
        let ids: Vec<u32> = plan.depots.iter().map(|d| d.depot_id).collect();
        assert_eq!(ids, vec![291551, 291554, 291556]);
        assert_eq!(plan.depots[2].dlc_app_id, 291560);

        let only32 = parse(
            r#""appinfo" { "config" { "installdir" "X" } "depots" { "1" { "config" { "osarch" "32" } "manifests" { "public" "5" } "maxsize" "10" } } }"#,
        );
        let plan = plan_app(1, &only32, &HashSet::new(), "english").unwrap();
        assert_eq!(plan.depots[0].depot_id, 1);
        assert_eq!(plan.depots[0].manifest_id, 5);
        assert_eq!(plan.depots[0].size, 10);
    }

    #[test]
    fn unsupported_language_falls_back_to_english_depots() {
        let info = parse(
            r#""appinfo" { "config" { "installdir" "X" } "depots" {
                "1" { "manifests" { "public" { "gid" "1" "size" "1" } } }
                "2" { "config" { "language" "english" } "manifests" { "public" { "gid" "2" "size" "1" } } }
                "3" { "config" { "language" "german" } "manifests" { "public" { "gid" "3" "size" "1" } } } } }"#,
        );
        let plan = plan_app(9, &info, &HashSet::new(), "koreana").unwrap();
        assert_eq!(plan.language, "english");
        let ids: Vec<u32> = plan.depots.iter().map(|d| d.depot_id).collect();
        assert_eq!(ids, vec![1, 2]);
        assert!(plan_app(
            9,
            &parse(r#""appinfo" { "depots" { } }"#),
            &HashSet::new(),
            "english"
        )
        .is_err());
    }
}
