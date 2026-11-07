//! Valve KeyValues text files (`.acf`, `libraryfolders.vdf`) as the Steam client writes them:
//! `"key"\t\t"value"`, tab indentation, `{`/`}` on their own lines, LF line endings, `\` and `"`
//! escaped. Parsing reuses `vdf::parse_text`, which keeps the file's key order, so a rewrite only
//! changes the keys that were set.

use crate::steam::vdf::{KVNode, KVValue};
use std::fs;
use std::path::Path;

pub fn parse_file(path: &Path) -> Option<KVNode> {
    let bytes = fs::read(path).ok()?;
    crate::steam::vdf::parse_text(&bytes)
}

pub fn escape(text: &str) -> String {
    let mut out = String::with_capacity(text.len());
    for c in text.chars() {
        match c {
            '\\' => out.push_str("\\\\"),
            '"' => out.push_str("\\\""),
            '\n' => out.push_str("\\n"),
            '\t' => out.push_str("\\t"),
            '\r' => out.push_str("\\r"),
            other => out.push(other),
        }
    }
    out
}

pub fn serialize(root: &KVNode) -> String {
    let mut out = String::new();
    write_node(&mut out, root, 0);
    out
}

fn write_node(out: &mut String, node: &KVNode, depth: usize) {
    let indent = "\t".repeat(depth);
    if node.is_object() {
        out.push_str(&format!("{indent}\"{}\"\n{indent}{{\n", escape(&node.name)));
        for child in &node.children {
            write_node(out, child, depth + 1);
        }
        out.push_str(&format!("{indent}}}\n"));
    } else {
        out.push_str(&format!(
            "{indent}\"{}\"\t\t\"{}\"\n",
            escape(&node.name),
            escape(&node.as_string(""))
        ));
    }
}

/// Writes `root` to `path` through a temp file in the same folder + rename.
pub fn write_file(path: &Path, root: &KVNode) -> Result<(), String> {
    let text = serialize(root);
    if crate::steam::depot_config::atomic_write_synced(path, text.as_bytes()) {
        Ok(())
    } else {
        Err(format!("write '{}' failed", path.display()))
    }
}

pub fn object(name: &str) -> KVNode {
    KVNode::new(name)
}

pub fn string(name: &str, value: impl Into<String>) -> KVNode {
    KVNode {
        name: name.to_string(),
        value: KVValue::String(value.into()),
        children: Vec::new(),
    }
}

pub fn get<'a>(node: &'a KVNode, key: &str) -> Option<&'a KVNode> {
    node.child(key)
}

pub fn get_str(node: &KVNode, key: &str) -> Option<String> {
    node.child(key)
        .filter(|c| !c.is_object())
        .map(|c| c.as_string(""))
}

pub fn get_u64(node: &KVNode, key: &str) -> u64 {
    get_str(node, key)
        .and_then(|v| v.trim().parse().ok())
        .unwrap_or(0)
}

pub fn get_mut<'a>(node: &'a mut KVNode, key: &str) -> Option<&'a mut KVNode> {
    node.children
        .iter_mut()
        .find(|c| c.name.eq_ignore_ascii_case(key))
}

/// Sets `key` to a string value, in place when present (keeping its position and spelling),
/// appended otherwise.
pub fn set(node: &mut KVNode, key: &str, value: impl Into<String>) {
    let value = value.into();
    match get_mut(node, key) {
        Some(child) => {
            child.value = KVValue::String(value);
            child.children.clear();
        }
        None => node.children.push(string(key, value)),
    }
}

/// The object child `key`, created (appended) when missing.
pub fn object_mut<'a>(node: &'a mut KVNode, key: &str) -> &'a mut KVNode {
    let idx = match node
        .children
        .iter()
        .position(|c| c.name.eq_ignore_ascii_case(key))
    {
        Some(idx) => {
            if !node.children[idx].is_object() {
                node.children[idx] = object(key);
            }
            idx
        }
        None => {
            node.children.push(object(key));
            node.children.len() - 1
        }
    };
    &mut node.children[idx]
}

pub fn remove(node: &mut KVNode, key: &str) -> bool {
    let before = node.children.len();
    node.children.retain(|c| !c.name.eq_ignore_ascii_case(key));
    before != node.children.len()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn writes_steam_layout_and_round_trips_escapes() {
        let mut root = object("AppState");
        set(&mut root, "appid", "228980");
        let scripts = object_mut(&mut root, "InstallScripts");
        set(
            scripts,
            "228988",
            "_CommonRedist\\vcredist\\2019\\installscript.vdf",
        );
        object_mut(&mut root, "UserConfig");
        let text = serialize(&root);
        assert_eq!(
            text,
            "\"AppState\"\n{\n\t\"appid\"\t\t\"228980\"\n\t\"InstallScripts\"\n\t{\n\t\t\"228988\"\t\t\"_CommonRedist\\\\vcredist\\\\2019\\\\installscript.vdf\"\n\t}\n\t\"UserConfig\"\n\t{\n\t}\n}\n"
        );
        let parsed = crate::steam::vdf::parse_text(text.as_bytes()).unwrap();
        assert_eq!(parsed, root);
    }

    #[test]
    fn set_keeps_position_and_spelling_and_appends_new_keys() {
        let mut root = crate::steam::vdf::parse_text(
            b"\"AppState\"\n{\n\t\"Name\"\t\t\"a\"\n\t\"x\"\t\t\"1\"\n}\n",
        )
        .unwrap();
        set(&mut root, "name", "b");
        set(&mut root, "y", "2");
        assert_eq!(
            serialize(&root),
            "\"AppState\"\n{\n\t\"Name\"\t\t\"b\"\n\t\"x\"\t\t\"1\"\n\t\"y\"\t\t\"2\"\n}\n"
        );
        assert!(remove(&mut root, "X"));
        assert!(!remove(&mut root, "x"));
        assert_eq!(get_u64(&root, "y"), 2);
    }
}
