#!/usr/bin/env python3
"""Compare native Android, bare glibc, and compatibility Vulkan captures.

Inputs may use DroidDeck's vkjson shape (one entry in ``devices``) or Vulkan
Profiles JSON (``capabilities.device``). Registry-typed values are normalized
without modifying captures. Missing fields become capability differences only
when the other producer is known to cover that field; otherwise they are
reported as incomplete capture coverage and fail closed.
"""

import argparse
import json
import math
from pathlib import Path
import re


SCHEMA = "droiddeck.vulkan-capability-report/v3"
MODES = ("native-android->glibc-bare", "glibc-bare->glibc-compat", "native-android->glibc-compat")
TRANSITIONS = {"lost", "gained", "changed"}
OWNERS = {"native", "compat", "unavailable"}
GROUPS = ("extensions", "features", "properties", "limits", "formats")
VKJSON_INFO_FIELDS = {
    "externalFenceProperties", "externalSemaphoreProperties", "layers", "memory", "queues",
    "queueFamiliesProperties",
}
VKJSON_ROOT_INFO_FIELDS = {"apiVersion", "deviceGroups", "extensions", "layers"}
IDENTITY_KEYS = ("vendorID", "deviceID", "deviceName", "driverVersion")
REGISTRY = json.loads((Path(__file__).with_name("registry_data.json")).read_text(encoding="utf-8"))
if REGISTRY.get("schema") != "droiddeck.vulkan-registry-metadata/v2":
    raise RuntimeError("unsupported Vulkan registry metadata")
FORMAT_NAMES = {int(value): name for value, name in REGISTRY["formats"].items()}
STRUCT_ALIASES = REGISTRY["struct_aliases"]
MEMBER_ALIASES = REGISTRY["member_aliases"]
STRUCT_MEMBERS = REGISTRY["struct_members"]
ENUM_TABLES = REGISTRY["enum_tables"]
BITMASK_TYPES = REGISTRY["bitmask_types"]
TYPE_ALIASES = REGISTRY["type_aliases"]
INTEGER_TYPES = {
    "char", "int8_t", "uint8_t", "int16_t", "uint16_t", "int32_t", "uint32_t",
    "int64_t", "uint64_t", "size_t", "VkDeviceAddress", "VkDeviceSize", "VkFlags",
    "VkFlags64", "VkSampleMask",
}
FLOAT_TYPES = {"float", "double"}
CORE_STRUCTS = {
    "11": ("VkPhysicalDeviceVulkan11Features", "VkPhysicalDeviceVulkan11Properties"),
    "12": ("VkPhysicalDeviceVulkan12Features", "VkPhysicalDeviceVulkan12Properties"),
    "13": ("VkPhysicalDeviceVulkan13Features", "VkPhysicalDeviceVulkan13Properties"),
    "14": ("VkPhysicalDeviceVulkan14Features", "VkPhysicalDeviceVulkan14Properties"),
}


class InputError(ValueError):
    """A supplied capture or policy has no supported, safe interpretation."""


def _value(value, numeric=False):
    """Stabilize JSON numbers without coercing unrecognized producer data."""
    if isinstance(value, float):
        if not math.isfinite(value):
            raise InputError("non-finite JSON number")
        return int(value) if value.is_integer() else value
    if numeric and isinstance(value, str) and re.fullmatch(r"[-+]?0[xX][0-9a-fA-F]+", value):
        return int(value, 0)
    if isinstance(value, list):
        return [_value(item, numeric=numeric) for item in value]
    if isinstance(value, dict):
        return {str(key): _value(item, numeric=numeric) for key, item in value.items()}
    return value


def _resolve(name, aliases):
    seen = set()
    while name in aliases and name not in seen:
        seen.add(name)
        name = aliases[name]
    return name


def _struct_name(name):
    name = _resolve(name, STRUCT_ALIASES)
    name = name.removeprefix("VkPhysicalDevice")
    return name[:1].lower() + name[1:]


def _capture_struct(name):
    """Resolve deterministic vkjson member spellings to one registry struct."""
    inferred = name if name.startswith("Vk") else "VkPhysicalDevice" + name[:1].upper() + name[1:]
    inferred = _resolve(inferred, STRUCT_ALIASES)
    if inferred in STRUCT_MEMBERS:
        return inferred

    def comparable(value):
        value = value.removeprefix("VkPhysicalDevice").removeprefix("Vk")
        value = re.sub(r"[^A-Za-z0-9]", "", value).lower()
        value = re.sub(r"(?:khr|ext)$", "", value)
        value = re.sub(r"^bit(8|16)", r"\1bit", value)
        value = re.sub(r"^formats4444", "4444formats", value)
        return value

    wanted = comparable(inferred)
    matches = sorted({_resolve(candidate, STRUCT_ALIASES) for candidate in STRUCT_MEMBERS
                      if comparable(candidate) == wanted})
    return matches[0] if len(matches) == 1 else inferred


def _canonical_member(struct, path):
    """Map a registry-verified promoted member to one canonical path."""
    if not struct.startswith("VkPhysicalDevice"):
        struct = "VkPhysicalDevice" + struct[:1].upper() + struct[1:]
    struct = _resolve(struct, STRUCT_ALIASES)
    member, separator, rest = path.partition(".")
    target = MEMBER_ALIASES.get("%s.%s" % (struct, member))
    if target and "::" in target:
        struct, member = target.split("::", 1)
        struct = _resolve(struct, STRUCT_ALIASES)
    suffix = separator + rest if separator else ""
    return "%s.%s%s" % (_struct_name(struct), member, suffix)


def _preferred_enum_name(table, number):
    names = [name for name, value in table.items() if value == number]
    if not names:
        return None
    vendor_suffix = re.compile(r"_(?:KHR|EXT|NV|NVX|AMD|AMDX|ARM|QCOM|IMG|INTEL|HUAWEI|VALVE|FUCHSIA|ANDROID|GOOGLE)$")
    return min(names, key=lambda name: (bool(vendor_suffix.search(name)), len(name), name))


def _enum_table(type_name):
    type_name = _resolve(type_name, TYPE_ALIASES)
    table_name = BITMASK_TYPES.get(type_name, type_name)
    return ENUM_TABLES.get(_resolve(table_name, TYPE_ALIASES))


def _unsupported(unsupported, path, reason, value=None):
    item = {"path": path, "reason": reason}
    if value is not None:
        item["value"] = _value(value)
    unsupported.append(item)


def _numeric(value, path, unsupported):
    result = _value(value, numeric=True)
    if isinstance(result, bool) or not isinstance(result, (int, float)):
        _unsupported(unsupported, path, "expected numeric registry value", value)
        return None
    return result


def _bitmask(value, type_name, path, unsupported):
    table = _enum_table(type_name)
    if not table:
        _unsupported(unsupported, path, "missing registry bitmask table", value)
        return None
    if isinstance(value, str) and value in table:
        number = table[value]
    elif isinstance(value, list):
        number = 0
        for item in value:
            if not isinstance(item, str) or item not in table:
                _unsupported(unsupported, path, "unknown bitmask name", item)
                return None
            number |= table[item]
    else:
        number = _numeric(value, path, unsupported)
        if not isinstance(number, int):
            return None
    remaining = number
    names = []
    for bit in sorted({value for value in table.values() if value > 0 and value & (value - 1) == 0}):
        if remaining & bit:
            names.append(_preferred_enum_name(table, bit))
            remaining &= ~bit
    if remaining:
        _unsupported(unsupported, path, "unknown bitmask bits", remaining)
        return None
    return names


def _enum(value, type_name, path, unsupported):
    table = _enum_table(type_name)
    if not table:
        _unsupported(unsupported, path, "missing registry enum table", value)
        return None
    if isinstance(value, str) and value in table:
        number = table[value]
    else:
        number = _numeric(value, path, unsupported)
        if not isinstance(number, int):
            return None
    name = _preferred_enum_name(table, number)
    if name is None:
        _unsupported(unsupported, path, "unknown enum value", number)
    return name


def _typed_scalar(value, type_name, descriptor, path, unsupported):
    type_name = _resolve(type_name, TYPE_ALIASES)
    table = _enum_table(type_name)
    if type_name == "char" and isinstance(value, str):
        return value
    if type_name == "VkBool32":
        value = _value(value)
        if value in (0, False):
            return False
        if value in (1, True):
            return True
        _unsupported(unsupported, path, "invalid VkBool32 value", value)
        return None
    if type_name in BITMASK_TYPES or (table and "FlagBits" in type_name):
        return _bitmask(value, type_name, path, unsupported)
    if table:
        return _enum(value, type_name, path, unsupported)
    if type_name in INTEGER_TYPES:
        number = _numeric(value, path, unsupported)
        if number is None:
            return None
        if not isinstance(number, int):
            _unsupported(unsupported, path, "expected integer registry value", value)
            return None
        if (type_name.startswith("uint") or type_name in {
                "size_t", "VkDeviceAddress", "VkDeviceSize", "VkFlags", "VkFlags64", "VkSampleMask"}) and number < 0:
            _unsupported(unsupported, path, "expected unsigned registry value", value)
            return None
        return number
    if type_name in FLOAT_TYPES:
        number = _numeric(value, path, unsupported)
        if number is None:
            return None
        # Vulkan `float` members are 32-bit values. Producers commonly print
        # either the exact binary value or six significant decimal digits.
        precision = 6 if type_name == "float" else 15
        return float(format(number, ".%dg" % precision))
    if type_name == "void" and descriptor.get("pointer"):
        return _value(value)
    _unsupported(unsupported, path, "unsupported registry member type %s" % type_name, value)
    return None


def _typed_member(value, descriptor, path, unsupported):
    type_name = _resolve(descriptor["type"], TYPE_ALIASES)
    struct = _resolve(type_name, STRUCT_ALIASES)
    if descriptor.get("pointer") and value in (None, "NULL"):
        return []
    if struct in STRUCT_MEMBERS:
        if isinstance(value, dict):
            return dict(_typed_leaves(value, struct, path, unsupported))
        if isinstance(value, list):
            result = []
            for index, item in enumerate(value):
                if not isinstance(item, dict):
                    _unsupported(unsupported, "%s.%d" % (path, index), "expected registry struct object", item)
                    return None
                result.append(dict(_typed_leaves(item, struct, "%s.%d" % (path, index), unsupported)))
            return result
        _unsupported(unsupported, path, "expected registry struct object", value)
        return None
    is_array = "fixed_length" in descriptor or "length" in descriptor
    if isinstance(value, list) and is_array:
        if type_name == "uint8_t" and all(isinstance(_value(item), int) and 0 <= _value(item) <= 255 for item in value):
            return "hex:" + bytes(_value(item) for item in value).hex()
        if type_name in BITMASK_TYPES or (type_name in ENUM_TABLES and "FlagBits" in type_name):
            return _bitmask(value, type_name, path, unsupported)
        result = []
        for index, item in enumerate(value):
            normalized = _typed_scalar(item, type_name, descriptor, "%s.%d" % (path, index), unsupported)
            if normalized is None:
                return None
            result.append(normalized)
        return result
    return _typed_scalar(value, type_name, descriptor, path, unsupported)


def _typed_leaves(value, struct, prefix, unsupported):
    """Yield known typed leaves and retain every unknown as coverage evidence."""
    struct = _resolve(struct, STRUCT_ALIASES)
    if not isinstance(value, dict) or struct not in STRUCT_MEMBERS:
        _unsupported(unsupported, prefix, "unknown or malformed registry structure %s" % struct, value)
        return
    members = STRUCT_MEMBERS[struct]
    for key in sorted(value):
        path = "%s.%s" % (prefix, key) if prefix else key
        descriptor = members.get(key)
        if descriptor is None:
            _unsupported(unsupported, path, "unrecognized registry structure member", value[key])
            continue
        type_name = _resolve(descriptor["type"], TYPE_ALIASES)
        child_struct = _resolve(type_name, STRUCT_ALIASES)
        child = value[key]
        if child_struct in STRUCT_MEMBERS and isinstance(child, dict):
            yield from _typed_leaves(child, child_struct, path, unsupported)
            continue
        normalized = _typed_member(child, descriptor, path, unsupported)
        if normalized is not None:
            if isinstance(normalized, dict):
                yield from normalized.items()
            else:
                yield path, normalized


def _new_group():
    return {"__sources__": {}, "__conflicts__": {}}


def _merge_leaf(target, name, value, source):
    """Keep only unambiguous values; conflicts retain all source observations."""
    sources = target["__sources__"]
    conflicts = target["__conflicts__"]
    if name in conflicts:
        conflicts[name].append({"source": source, "value": value})
        return
    if name not in target:
        target[name] = value
        sources[name] = source
        return
    if target[name] == value:
        previous = sources[name]
        sources[name] = previous if source in previous.split("|") else previous + "|" + source
        return
    conflicts[name] = [
        {"source": sources.pop(name), "value": target.pop(name)},
        {"source": source, "value": value},
    ]


def _finish_group(group):
    values = dict(sorted((key, value) for key, value in group.items() if not key.startswith("__")))
    conflicts = []
    for path, observations in sorted(group["__conflicts__"].items()):
        conflicts.append({"path": path, "observations": observations,
                          "reason": "conflicting observations for one canonical registry member"})
    return values, conflicts


def _collect_structs(value, suffix, target, coverage, unsupported, source_prefix="device"):
    if not isinstance(value, dict):
        return
    for key, item in value.items():
        if suffix in key and isinstance(item, dict):
            struct = _capture_struct(key)
            if struct not in STRUCT_MEMBERS:
                _unsupported(unsupported, "%s.%s" % (source_prefix, key), "unrecognized capability structure", item)
                continue
            for path, leaf in _typed_leaves(item, struct, "", unsupported):
                name = "base.%s" % path if struct == "VkPhysicalDevice%s" % suffix else _canonical_member(struct, path)
                coverage.add(name)
                _merge_leaf(target, name, leaf, "%s.%s" % (source_prefix, key))
        elif isinstance(item, dict):
            _collect_structs(item, suffix, target, coverage, unsupported, "%s.%s" % (source_prefix, key))


def _extensions(value):
    if isinstance(value, dict):
        result = {}
        for key, item in value.items():
            if key in result:
                raise InputError("duplicate extension %s" % key)
            result[str(key)] = _value(item, numeric=True)
        return dict(sorted(result.items()))
    if isinstance(value, list):
        result = {}
        for entry in value:
            if not isinstance(entry, dict) or not isinstance(entry.get("extensionName"), str):
                raise InputError("vkjson extension entry is missing extensionName")
            name = entry["extensionName"]
            if name in result:
                raise InputError("duplicate vkjson extension %s" % name)
            result[name] = _value(entry.get("specVersion"), numeric=True)
        return dict(sorted(result.items()))
    raise InputError("extensions must be an object or vkjson extension list")


def _format_name(value, unsupported):
    if isinstance(value, str) and value in ENUM_TABLES["VkFormat"]:
        number = ENUM_TABLES["VkFormat"][value]
    else:
        number = _value(value, numeric=True)
    if not isinstance(number, int) or number not in FORMAT_NAMES:
        _unsupported(unsupported, "formats.%s" % value, "unknown VkFormat value", value)
        return None
    return _preferred_enum_name(ENUM_TABLES["VkFormat"], number)


def _format_properties(name, details, unsupported):
    if not isinstance(details, dict):
        raise InputError("format details must be an object")
    blocks = details if "VkFormatProperties" in details else {"VkFormatProperties": details}
    block = blocks.get("VkFormatProperties")
    if not isinstance(block, dict):
        _unsupported(unsupported, "formats.%s" % name, "missing VkFormatProperties", details)
        return {}, set()
    base = dict(_typed_leaves(block, "VkFormatProperties", "format.%s" % name, unsupported))
    # Supported-only Profiles captures omit zero-valued fields while vkjson
    # writes them. Base format support is closed-world, so zero and absent are
    # the same capability observation.
    result = {path: value for path, value in base.items() if bool(value)}
    coverage = set(result)
    for struct, value in blocks.items():
        if struct == "VkFormatProperties":
            continue
        canonical = _resolve(struct, STRUCT_ALIASES)
        if canonical not in STRUCT_MEMBERS or not isinstance(value, dict):
            _unsupported(unsupported, "formats.%s.%s" % (name, struct), "unrecognized format structure", value)
            continue
        short = canonical.removeprefix("Vk")
        short = short[:1].lower() + short[1:]
        leaves = dict(_typed_leaves(value, canonical, "format.%s.%s" % (name, short), unsupported))
        result.update(leaves)
        coverage.update(leaves)
    return result, coverage


def _formats(value):
    result, unsupported, coverage = {}, [], {"base:*"}
    entries = []
    if isinstance(value, dict):
        entries = list(value.items())
    elif isinstance(value, list):
        for entry in value:
            if not isinstance(entry, list) or len(entry) != 2:
                raise InputError("vkjson format entry must be [format-id, properties]")
            entries.append((entry[0], entry[1]))
    else:
        raise InputError("formats must be an object or vkjson format list")
    seen = set()
    for raw_name, details in entries:
        name = _format_name(raw_name, unsupported)
        if name is None:
            continue
        if name in seen:
            raise InputError("duplicate format %s" % name)
        seen.add(name)
        leaves, observed = _format_properties(name, details, unsupported)
        # vkjson emits every known format, including all-zero unsupported rows;
        # Profiles emits supported formats. Treat those representations equally.
        if any(bool(item) for item in leaves.values()):
            result.update(leaves)
            coverage.update(path for path in observed if path.count(".") > 2)
    return dict(sorted(result.items())), unsupported, coverage


def _base_features(value, target, coverage, unsupported, source):
    for path, leaf in _typed_leaves(value, "VkPhysicalDeviceFeatures", "", unsupported):
        name = "base.%s" % path
        coverage.add(name)
        _merge_leaf(target, name, leaf, source)


def _base_properties(value, properties, limits, coverage, unsupported, source):
    identity = {}
    for path, leaf in _typed_leaves(value, "VkPhysicalDeviceProperties", "", unsupported):
        if path in IDENTITY_KEYS:
            identity[path] = leaf
            continue
        if path.startswith("limits."):
            name = path.removeprefix("limits.")
            coverage["limits"].add(name)
            _merge_leaf(limits, name, leaf, source)
        else:
            name = "base.%s" % path
            coverage["properties"].add(name)
            _merge_leaf(properties, name, leaf, source)
    return identity


def _core_blocks(device, features, properties, coverage, unsupported):
    for core_name, core in device.items():
        match = re.fullmatch(r"core(\d)(\d)", core_name)
        if not match or not isinstance(core, dict):
            continue
        version = "".join(match.groups())
        structs = CORE_STRUCTS.get(version)
        if not structs:
            _unsupported(unsupported, "device.%s" % core_name, "unsupported core capability block", core)
            continue
        for block, target, group, struct in (
                (core.get("features"), features, "features", structs[0]),
                (core.get("properties"), properties, "properties", structs[1])):
            if block is None:
                continue
            for path, leaf in _typed_leaves(block, struct, "", unsupported):
                name = _canonical_member(struct, path)
                coverage[group].add(name)
                _merge_leaf(target, name, leaf, core_name)
        extras = set(core) - {"features", "properties"}
        for key in sorted(extras):
            _unsupported(unsupported, "device.%s.%s" % (core_name, key), "unrecognized core block field", core[key])


def _final_view(identity, extensions, features, properties, limits, formats, coverage,
                unsupported, unknown, info):
    features, feature_conflicts = _finish_group(features)
    properties, property_conflicts = _finish_group(properties)
    limits, limit_conflicts = _finish_group(limits)
    for item in feature_conflicts:
        coverage["features"].discard(item["path"])
    for item in property_conflicts:
        coverage["properties"].discard(item["path"])
    for item in limit_conflicts:
        coverage["limits"].discard(item["path"])
    return {
        "identity": dict(sorted(identity.items())),
        "extensions": extensions,
        "features": features,
        "properties": properties,
        "limits": limits,
        "formats": formats,
        "coverage": {group: sorted(paths) for group, paths in coverage.items()},
        "unknown": dict(sorted(unknown.items())),
        "info": dict(sorted(info.items())),
        "unsupported": sorted(unsupported, key=lambda item: (item["path"], item["reason"])),
        "input_inconsistencies": feature_conflicts + property_conflicts + limit_conflicts,
    }


def _profile_view(device):
    required = {"extensions", "features", "properties", "formats"}
    if not required.issubset(device):
        raise InputError("Vulkan Profiles device is missing extensions, features, properties, or formats")
    if not isinstance(device["properties"], dict) or not isinstance(device["features"], dict):
        raise InputError("Vulkan Profiles features and properties must be objects")
    features, properties, limits = _new_group(), _new_group(), _new_group()
    coverage = {group: set() for group in GROUPS}
    coverage["extensions"].add("*")
    unsupported = []
    _collect_structs(device["features"], "Features", features, coverage["features"], unsupported, "features")
    core = device["properties"].get("VkPhysicalDeviceProperties")
    if not isinstance(core, dict):
        raise InputError("Vulkan Profiles is missing VkPhysicalDeviceProperties")
    identity = _base_properties(core, properties, limits, coverage, unsupported, "VkPhysicalDeviceProperties")
    for struct, block in device["properties"].items():
        if struct == "VkPhysicalDeviceProperties":
            continue
        if not isinstance(block, dict) or "Properties" not in struct:
            _unsupported(unsupported, "properties.%s" % struct, "unrecognized property structure", block)
            continue
        _collect_structs({struct: block}, "Properties", properties, coverage["properties"], unsupported, "properties")
    _core_blocks(device, features, properties, coverage, unsupported)
    formats, format_unsupported, format_coverage = _formats(device["formats"])
    unsupported.extend(format_unsupported)
    coverage["formats"].update(format_coverage)
    known = required | {"queueFamiliesProperties"}
    known.update(key for key in device if re.fullmatch(r"core\d\d", key))
    unknown = {key: _value(value) for key, value in device.items() if key not in known}
    for key, value in unknown.items():
        _unsupported(unsupported, "device.%s" % key, "unrecognized device field", value)
    info = {}
    if "queueFamiliesProperties" in device:
        info["queueFamiliesProperties"] = _value(device["queueFamiliesProperties"])
    return _final_view(identity, _extensions(device["extensions"]), features, properties, limits,
                       formats, coverage, unsupported, unknown, info)


def _vkjson_view(device):
    required = {"extensions", "features", "properties", "formats"}
    if not required.issubset(device):
        raise InputError("vkjson device is missing extensions, features, properties, or formats")
    if not isinstance(device["features"], dict) or not isinstance(device["properties"], dict):
        raise InputError("vkjson features and properties must be objects")
    features, properties, limits = _new_group(), _new_group(), _new_group()
    coverage = {group: set() for group in GROUPS}
    coverage["extensions"].add("*")
    unsupported = []
    _base_features(device["features"], features, coverage["features"], unsupported, "features")
    identity = _base_properties(device["properties"], properties, limits, coverage, unsupported, "properties")
    extended = {key: value for key, value in device.items() if key not in {"features", "properties"}}
    _collect_structs(extended, "Features", features, coverage["features"], unsupported)
    _collect_structs(extended, "Properties", properties, coverage["properties"], unsupported)
    _core_blocks(device, features, properties, coverage, unsupported)
    formats, format_unsupported, format_coverage = _formats(device["formats"])
    unsupported.extend(format_unsupported)
    coverage["formats"].update(format_coverage)
    known = set(required) | VKJSON_INFO_FIELDS
    known.update(key for key in device if re.fullmatch(r"core\d\d", key))
    for key, value in device.items():
        if isinstance(value, dict) and ("Features" in key or "Properties" in key):
            known.add(key)
        if key.startswith("VK_") and isinstance(value, dict):
            known.add(key)
    unknown = {key: _value(value) for key, value in device.items() if key not in known}
    for key, value in unknown.items():
        _unsupported(unsupported, "device.%s" % key, "unrecognized vkjson device field", value)
    info = {key: _value(device[key]) for key in sorted(VKJSON_INFO_FIELDS & set(device))}
    return _final_view(identity, _extensions(device["extensions"]), features, properties, limits,
                       formats, coverage, unsupported, unknown, info)


def normalize_view(document):
    """Normalize one supported capture to deterministic, JSON-serializable data."""
    if not isinstance(document, dict):
        raise InputError("capture root must be an object")
    is_profile = isinstance(document.get("capabilities"), dict)
    is_vkjson = isinstance(document.get("devices"), list)
    if is_profile and is_vkjson:
        raise InputError("capture cannot contain both Profiles capabilities and vkjson devices")
    if is_profile:
        device = document["capabilities"].get("device")
        if not isinstance(device, dict):
            raise InputError("Vulkan Profiles capture is missing capabilities.device")
        view = _profile_view(device)
        known = {"$schema", "capabilities", "profiles"}
        view["info"]["root"] = {key: _value(document[key]) for key in ("$schema", "profiles") if key in document}
    elif is_vkjson:
        devices = document["devices"]
        if len(devices) != 1 or not isinstance(devices[0], dict):
            raise InputError("vkjson capture must contain exactly one object in devices")
        view = _vkjson_view(devices[0])
        known = {"devices"} | VKJSON_ROOT_INFO_FIELDS
        view["info"]["root"] = {
            key: _value(document[key]) for key in sorted(VKJSON_ROOT_INFO_FIELDS & set(document))}
    else:
        raise InputError("unsupported capture: expected vkjson devices or Vulkan Profiles capabilities.device")
    for key in sorted(set(document) - known):
        path = "root.%s" % key
        view["unknown"][path] = _value(document[key])
        _unsupported(view["unsupported"], path, "unrecognized capture root field", document[key])
    view["unsupported"].sort(key=lambda item: (item["path"], item["reason"]))
    return view


def _json_object(pairs):
    value = {}
    for key, item in pairs:
        if key in value:
            raise InputError("duplicate JSON key %s" % key)
        value[key] = item
    return value


def _json_load(source):
    return json.load(source, object_pairs_hook=_json_object)


def load_view(path):
    try:
        with Path(path).open(encoding="utf-8") as source:
            return normalize_view(_json_load(source))
    except OSError as error:
        raise InputError("cannot read %s: %s" % (path, error)) from error
    except json.JSONDecodeError as error:
        raise InputError("invalid JSON in %s: %s" % (path, error)) from error


def load_policy(path):
    if path is None:
        return []
    try:
        with Path(path).open(encoding="utf-8") as source:
            value = _json_load(source)
    except OSError as error:
        raise InputError("cannot read policy %s: %s" % (path, error)) from error
    except json.JSONDecodeError as error:
        raise InputError("invalid policy JSON in %s: %s" % (path, error)) from error
    if not isinstance(value, dict) or set(value) != {"expected_differences"} or not isinstance(value["expected_differences"], list):
        raise InputError("policy must contain only an expected_differences array")
    entries = value["expected_differences"]
    required = {"mode", "path", "transition", "reason", "owner", "before", "after"}
    seen = set()
    for entry in entries:
        if not isinstance(entry, dict) or set(entry) != required:
            raise InputError("policy entry needs exactly mode, path, transition, reason, owner, before, and after")
        if not all(isinstance(entry[key], str) for key in ("mode", "path", "transition", "reason", "owner")):
            raise InputError("policy mode, path, transition, reason, and owner must be strings")
        if not entry["reason"]:
            raise InputError("policy reason must not be empty")
        if entry["mode"] not in MODES or entry["transition"] not in TRANSITIONS or entry["owner"] not in OWNERS:
            raise InputError("policy entry has invalid mode, transition, or owner")
        if "*" in entry["path"] or not any(entry["path"].startswith(group + ".") for group in GROUPS):
            raise InputError("policy path must name one capability path without wildcards")
        if entry["transition"] == "lost" and entry["after"] is not None:
            raise InputError("lost policy entry must have after null")
        if entry["transition"] == "gained" and entry["before"] is not None:
            raise InputError("gained policy entry must have before null")
        if entry["transition"] == "changed" and (entry["before"] is None or entry["after"] is None or entry["before"] == entry["after"]):
            raise InputError("changed policy entry needs distinct non-null values")
        for key in ("before", "after"):
            value = entry[key]
            if isinstance(value, dict) or (isinstance(value, list)
                                          and any(isinstance(item, (dict, list)) for item in value)):
                raise InputError("policy before and after must be scalar capability values or flat arrays")
        key = (entry["mode"], entry["path"])
        if key in seen:
            raise InputError("duplicate policy entry for %s %s" % key)
        seen.add(key)
    return entries


def _policy_match(change, policy):
    for entry in policy:
        if (all(entry[key] == change[key] for key in ("mode", "path", "transition"))
                and _json_value_equal(entry["before"], change["before"])
                and _json_value_equal(entry["after"], change["after"])):
            return entry
    return None


def _json_value_equal(left, right):
    """Compare canonical JSON leaves without Python's bool/int equivalence."""
    if isinstance(left, bool) or isinstance(right, bool):
        return type(left) is type(right) and left == right
    if isinstance(left, (int, float)) and isinstance(right, (int, float)):
        return left == right
    if isinstance(left, list) and isinstance(right, list):
        return len(left) == len(right) and all(
            _json_value_equal(left_item, right_item) for left_item, right_item in zip(left, right))
    return type(left) is type(right) and left == right


def _default_owner(_mode, _transition):
    # Direction establishes where a value was observed, not which layer caused
    # it. Only an exact reviewed policy may assign native or compat ownership.
    return "unavailable"


def _covered(view, group, key):
    coverage = set(view.get("coverage", {}).get(group, []))
    if group == "formats" and "base:*" in coverage:
        if key.count(".") == 2 and key.rsplit(".", 1)[-1] in STRUCT_MEMBERS["VkFormatProperties"]:
            return True
    return "*" in coverage or key in coverage


def _differences(source, target, mode, policy):
    result, coverage_unknown, matched = [], [], set()
    for group in GROUPS:
        before_group, after_group = source[group], target[group]
        for key in sorted(set(before_group) | set(after_group)):
            in_before, in_after = key in before_group, key in after_group
            before = before_group.get(key)
            after = after_group.get(key)
            if not in_after:
                transition = "lost"
                if not _covered(target, group, key):
                    coverage_unknown.append({"mode": mode, "path": "%s.%s" % (group, key),
                                             "observed_in": mode.split("->", 1)[0], "value": before,
                                             "missing_from": mode.split("->", 1)[1]})
                    continue
            elif not in_before:
                transition = "gained"
                if not _covered(source, group, key):
                    coverage_unknown.append({"mode": mode, "path": "%s.%s" % (group, key),
                                             "observed_in": mode.split("->", 1)[1], "value": after,
                                             "missing_from": mode.split("->", 1)[0]})
                    continue
            elif before == after:
                continue
            else:
                transition = "changed"
            change = {"mode": mode, "path": "%s.%s" % (group, key), "transition": transition,
                      "before": before if in_before else None, "after": after if in_after else None}
            entry = _policy_match(change, policy)
            if entry:
                change.update({"expected": True, "reason": entry["reason"], "owner": entry["owner"]})
                matched.add(id(entry))
            else:
                change.update({"expected": False, "owner": _default_owner(mode, transition)})
            result.append(change)
    return result, coverage_unknown, matched


def _counts(changes):
    counts = {}
    for change in changes:
        group = change["path"].split(".", 1)[0]
        counts[group] = counts.get(group, 0) + 1
    return dict(sorted(counts.items()))


def _group_coverage_unknown(items):
    """Collapse repeated per-format schema gaps without treating them as deltas."""
    grouped, ordinary = {}, []
    format_pattern = re.compile(r"^formats\.format\.([^.]+)\.(.+)$")
    struct_pattern = re.compile(r"^(features|properties)\.([^.]+)\.(.+)$")
    for item in items:
        match = format_pattern.match(item["path"])
        if match and "." in match.group(2):
            schema_path = "formats.format.*.%s" % match.group(2)
        else:
            match = struct_pattern.match(item["path"])
            schema_path = "%s.%s.*" % (match.group(1), match.group(2)) if match else None
        if schema_path is None:
            ordinary.append(item)
            continue
        key = (item["mode"], schema_path, item["observed_in"], item["missing_from"])
        bucket = grouped.setdefault(key, {"mode": item["mode"], "path": schema_path,
                                          "observed_in": item["observed_in"],
                                          "missing_from": item["missing_from"],
                                          "count": 0, "examples": [], "observed_values": {}})
        bucket["count"] += 1
        if len(bucket["examples"]) < 5:
            bucket["examples"].append(item["path"])
        value = json.dumps(item["value"], sort_keys=True, separators=(",", ":"))
        bucket["observed_values"][value] = bucket["observed_values"].get(value, 0) + 1
    for key in sorted(grouped):
        bucket = grouped[key]
        if bucket["count"] == 1:
            original = next(item for item in items if item["path"] == bucket["examples"][0]
                            and item["observed_in"] == bucket["observed_in"]
                            and item["missing_from"] == bucket["missing_from"])
            ordinary.append(original)
        else:
            ordinary.append(bucket)
    return sorted(ordinary, key=lambda item: item["path"])


def compare_views(native, bare, compat, policy=()):
    """Produce a deterministic report; callers handle InputError fail-closed."""
    for name, view in (("native-android", native), ("glibc-bare", bare), ("glibc-compat", compat)):
        if not isinstance(view, dict):
            raise InputError("%s view is missing" % name)
    mismatches = []
    for key in IDENTITY_KEYS:
        baseline = native["identity"].get(key)
        for name, view in (("glibc-bare", bare), ("glibc-compat", compat)):
            other = view["identity"].get(key)
            if baseline is None or other is None or baseline != other:
                mismatches.append({"field": key, "mode": name, "native": baseline, "other": other})
    comparisons, observed = {}, set()
    views = {"native-android": native, "glibc-bare": bare, "glibc-compat": compat}
    for mode in MODES:
        source_name, target_name = mode.split("->", 1)
        changes, coverage_unknown, used = _differences(views[source_name], views[target_name], mode, policy)
        observed.update(used)
        expected = [change for change in changes if change["expected"]]
        unexpected = [change for change in changes if not change["expected"]]
        coverage_observation_count = len(coverage_unknown)
        coverage_unknown = _group_coverage_unknown(coverage_unknown)
        comparisons[mode] = {
            "expected": expected,
            "unexpected": unexpected,
            "coverage_unknown": coverage_unknown,
            "summary": {"expected_count": len(expected), "unexpected_count": len(unexpected),
                        "coverage_unknown_count": coverage_observation_count,
                        "coverage_unknown_group_count": len(coverage_unknown),
                        "unexpected_by_group": _counts(unexpected)},
            "ownership": {owner: [change["path"] for change in changes if change["owner"] == owner]
                          for owner in sorted(OWNERS)},
        }
    stale = [entry for entry in policy if id(entry) not in observed]
    unsupported = {name: view.get("unsupported", []) for name, view in views.items()}
    unknown = {name: view.get("unknown", {}) for name, view in views.items()}
    capture_info = {name: view.get("info", {}) for name, view in views.items()}
    inconsistencies = {name: view.get("input_inconsistencies", []) for name, view in views.items()}
    differences_ok = (not mismatches and not stale
                      and all(not comparison["unexpected"] for comparison in comparisons.values()))
    coverage_complete = (not any(unsupported.values()) and not any(inconsistencies.values())
                         and all(not comparison["coverage_unknown"] for comparison in comparisons.values()))
    return {
        "schema": SCHEMA,
        "registry": REGISTRY["source"],
        "identity": native["identity"],
        "identity_mismatches": mismatches,
        "comparisons": comparisons,
        "unmatched_policy": stale,
        "unsupported_coverage": unsupported,
        "unrecognized_fields": unknown,
        "capture_info": capture_info,
        "input_inconsistencies": inconsistencies,
        "differences_ok": differences_ok,
        "coverage_complete": coverage_complete,
        "ok": differences_ok and coverage_complete,
    }


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--native", required=True, type=Path, help="native Android vkjson or Profiles capture")
    parser.add_argument("--bare", required=True, type=Path, help="glibc bare Vulkan Profiles capture")
    parser.add_argument("--compat", required=True, type=Path, help="glibc compatibility Vulkan Profiles capture")
    parser.add_argument("--policy", type=Path, help="exact expected-difference policy JSON")
    parser.add_argument("--output", type=Path, help="write the JSON report to this path instead of stdout")
    args = parser.parse_args(argv)
    try:
        result = compare_views(load_view(args.native), load_view(args.bare), load_view(args.compat), load_policy(args.policy))
    except InputError as error:
        rendered = json.dumps({"schema": SCHEMA, "registry": REGISTRY["source"],
                               "ok": False, "error": str(error)}, sort_keys=True, indent=2) + "\n"
        if args.output:
            args.output.write_text(rendered, encoding="utf-8")
        else:
            print(rendered, end="")
        return 2
    rendered = json.dumps(result, sort_keys=True, indent=2) + "\n"
    if args.output:
        args.output.write_text(rendered, encoding="utf-8")
    else:
        print(rendered, end="")
    return 0 if result["ok"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
