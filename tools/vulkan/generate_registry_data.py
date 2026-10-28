#!/usr/bin/env python3
"""Generate typed capability metadata from one pinned Vulkan Registry vk.xml.

Usage: generate_registry_data.py VK_XML OUTPUT_JSON

The generated file deliberately contains data, not executable registry parsing
logic. capability_report.py therefore has no network or third-party runtime
dependency and can compare captures made by differently shaped producers.
"""

import hashlib
import json
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ElementTree


REVISION = "8d6039a455a7ecc7d2a592ff97f62db4e59b70bf"
SHA256 = "fb8f7647fd6439fe5deb423fd55c9186c0b1253c75d61b2399a01c5cfd8b98af"


def _integer(text):
    """Parse Registry integer spellings; return None for C expressions."""
    if text is None:
        return None
    text = re.sub(r"(?:ULL|LLU|UL|LU|U|L)$", "", text.strip())
    try:
        return int(text, 0)
    except ValueError:
        return None


def _raw_value(entry, extension_number=None):
    value = _integer(entry.get("value"))
    if value is not None:
        return value
    bitpos = _integer(entry.get("bitpos"))
    if bitpos is not None:
        return 1 << bitpos
    offset = _integer(entry.get("offset"))
    if offset is not None:
        number = _integer(entry.get("extnumber"))
        if number is None:
            number = extension_number
        if number is None:
            return None
        value = 1_000_000_000 + (number - 1) * 1_000 + offset
        return -value if entry.get("dir") == "-" else value
    return None


def _enum_entries(root):
    """Return every enum extending a typed table, with parent extension data."""
    result = []
    for enums in root.findall("./enums"):
        table = enums.get("name")
        if table:
            result.extend((table, entry, None) for entry in enums.findall("enum"))
    for require in root.findall("./feature/require"):
        result.extend((entry.get("extends"), entry, None)
                      for entry in require.findall("enum") if entry.get("extends"))
    for extension in root.findall("./extensions/extension"):
        number = _integer(extension.get("number"))
        for require in extension.findall("require"):
            result.extend((entry.get("extends"), entry, number)
                          for entry in require.findall("enum") if entry.get("extends"))
    return result


def _enum_tables(root):
    table_kinds = {
        enums.get("name"): enums.get("type")
        for enums in root.findall("./enums")
        if enums.get("name") and enums.get("type") in {"enum", "bitmask"}
    }
    entries = [(table, entry, number) for table, entry, number in _enum_entries(root)
               if table in table_kinds]
    values = {}
    tables = {name: {} for name in table_kinds}
    pending = entries
    while pending:
        later = []
        progress = False
        for table, entry, extension_number in pending:
            name = entry.get("name")
            if not name:
                continue
            alias = entry.get("alias")
            value = values.get(alias) if alias else _raw_value(entry, extension_number)
            if value is None:
                later.append((table, entry, extension_number))
                continue
            prior = tables[table].get(name)
            if prior is not None and prior != value:
                raise SystemExit("conflicting enum value for %s" % name)
            tables[table][name] = value
            values[name] = value
            progress = True
        if not progress:
            break
        pending = later
    return {name: dict(sorted(table.items())) for name, table in sorted(tables.items())}


def _type_aliases(root):
    result = {}
    for item in root.findall("./types/type[@alias]"):
        name = item.get("name") or item.findtext("name")
        if name:
            result[name] = item.get("alias")
    return result


def _resolve_alias(name, aliases):
    seen = set()
    while name in aliases and name not in seen:
        seen.add(name)
        name = aliases[name]
    return name


def _member_descriptor(member):
    name_node = member.find("name")
    member_type = member.findtext("type")
    text = "".join(member.itertext())
    tail = name_node.tail or "" if name_node is not None else ""
    fixed = re.search(r"\[\s*([^\]]+)\s*\]", tail)
    fixed_enum = member.findtext("enum") if "[" in text else None
    pointer = "*" in text
    descriptor = {"type": member_type, "pointer": pointer}
    if member.get("len"):
        descriptor["length"] = member.get("len")
    if fixed:
        descriptor["fixed_length"] = fixed.group(1)
    elif fixed_enum:
        descriptor["fixed_length"] = fixed_enum
    return descriptor


def _struct_metadata(root, type_aliases):
    all_structs = {}
    direct_member_aliases = {}
    struct_aliases = {}
    for item in root.findall("./types/type[@category='struct']"):
        name = item.get("name") or item.findtext("name")
        if not name:
            continue
        if item.get("alias"):
            struct_aliases[name] = item.get("alias")
            continue
        members = {}
        for member in item.findall("member"):
            member_name = member.findtext("name")
            member_type = member.findtext("type")
            if not member_name or not member_type:
                continue
            members[member_name] = _member_descriptor(member)
            name_node = member.find("name")
            alias = name_node.get("alias") if name_node is not None else None
            if alias:
                direct_member_aliases["%s.%s" % (name, member_name)] = alias
        all_structs[name] = members

    # Keep every struct's member typing. Captures may legally include nested or
    # auxiliary query structs (for example per-format extension properties).
    # The XML itself is not shipped, and this remains data-only metadata.
    seeds = set(all_structs)
    selected = set()
    pending = list(seeds)
    while pending:
        name = _resolve_alias(pending.pop(), struct_aliases)
        if name in selected or name not in all_structs:
            continue
        selected.add(name)
        for descriptor in all_structs[name].values():
            child = _resolve_alias(descriptor["type"], type_aliases)
            if child in all_structs:
                pending.append(child)
    selected_aliases = {name: target for name, target in struct_aliases.items()
                        if _resolve_alias(target, struct_aliases) in selected and name.startswith("VkPhysicalDevice")}
    structs = {name: all_structs[name] for name in sorted(selected)}
    return structs, selected_aliases, direct_member_aliases, all_structs, struct_aliases


def _promotion_aliases(root, structs, all_structs, struct_aliases, type_aliases, aliases):
    """Derive core aggregate aliases only from registry-declared promotions."""
    result = dict(aliases)
    evidence = {}
    for extension in root.findall("./extensions/extension[@promotedto]"):
        promoted = extension.get("promotedto", "")
        match = re.fullmatch(r"VK_VERSION_(\d+)_(\d+)", promoted)
        if not match:
            continue
        version = "".join(match.groups())
        required_types = [item.get("name") for item in extension.findall("require/type") if item.get("name")]
        for kind in ("Features", "Properties"):
            core = "VkPhysicalDeviceVulkan%s%s" % (version, kind)
            if core not in structs:
                continue
            candidates = []
            for name in required_types:
                canonical = _resolve_alias(name, struct_aliases)
                if canonical == core or canonical not in all_structs:
                    continue
                if canonical.startswith("VkPhysicalDevice") and kind in canonical:
                    candidates.append(canonical)
            for member, descriptor in structs[core].items():
                if member in {"sType", "pNext"}:
                    continue
                key = "%s.%s" % (core, member)
                if key in result:
                    evidence[key] = {"core": promoted, "extension": extension.get("name"),
                                     "source": result[key].split("::", 1)[0]}
                    continue
                matches = []
                core_type = _resolve_alias(descriptor["type"], type_aliases)
                for candidate in sorted(set(candidates)):
                    other = all_structs[candidate].get(member)
                    if other and _resolve_alias(other["type"], type_aliases) == core_type:
                        matches.append(candidate)
                if len(matches) == 1:
                    result[key] = "%s::%s" % (matches[0], member)
                    evidence[key] = {"core": promoted, "extension": extension.get("name"),
                                     "source": matches[0]}
    return dict(sorted(result.items())), dict(sorted(evidence.items()))


def main(argv=None):
    args = argv or sys.argv[1:]
    if len(args) != 2:
        raise SystemExit(__doc__.strip())
    source, output = map(Path, args)
    raw = source.read_bytes()
    digest = hashlib.sha256(raw).hexdigest()
    if digest != SHA256:
        raise SystemExit("registry hash mismatch; expected %s at %s" % (SHA256, REVISION))
    root = ElementTree.fromstring(raw)
    enum_tables = _enum_tables(root)
    type_aliases = _type_aliases(root)
    structs, struct_aliases, aliases, all_structs, all_struct_aliases = _struct_metadata(root, type_aliases)
    aliases, promotions = _promotion_aliases(
        root, structs, all_structs, all_struct_aliases, type_aliases, aliases)
    bitmask_types = {}
    for item in root.findall("./types/type[@category='bitmask']"):
        name = item.get("name") or item.findtext("name")
        bits = item.get("bitvalues") or item.get("requires")
        if name and bits:
            bitmask_types[name] = bits
    formats = enum_tables["VkFormat"]
    data = {
        "schema": "droiddeck.vulkan-registry-metadata/v2",
        "source": {"file": "vk.xml", "revision": REVISION, "sha256": digest,
                   "repository": "https://github.com/KhronosGroup/Vulkan-Headers"},
        "formats": {str(value): name for name, value in sorted(formats.items(), key=lambda item: (item[1], item[0]))},
        "format_feature_bits": enum_tables["VkFormatFeatureFlagBits"],
        "enum_tables": enum_tables,
        "bitmask_types": dict(sorted(bitmask_types.items())),
        "type_aliases": dict(sorted(type_aliases.items())),
        "struct_members": structs,
        "member_aliases": aliases,
        "promotion_evidence": promotions,
        "struct_aliases": dict(sorted(struct_aliases.items())),
    }
    output.write_text(json.dumps(data, indent=2, sort_keys=True) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
