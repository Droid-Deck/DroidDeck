#!/usr/bin/env python3
"""Builds DroidDeck Proton (Auto)'s recipe library from community game configs.

    tools/recipes/convert.py --bannerhub <bannerhub-game-configs> --bannerlator <bannerlator-game-configs> \
        [--catalog nightlies.json] [--out app/src/main/assets/recipes/default.json] [--report docs/...md]

Input: The412Banner/bannerhub-game-configs (one JSON per uploaded GameHub/BannerHub config) and
The412Banner/bannerlator-game-configs (which config folder is which Steam app). Those configs come
from Android Wine containers, so most of what they hold does not apply here. Only what carries over
to Valve's ARM64 Proton under DroidDeck is kept, and only where independent uploads agree:

  FEX     From arm64x containers (the same ARM64 Wine with FEX as Valve's ARM64 Proton), on any GPU:
          FEX's job is the CPU. Only configs where the player chose a FEX profile or changed a
          setting count, and a value is kept when it differs from FEX's own default.
  DXVK    Per Adreno family. A build most uploads carry on that family is the app's default of
  VKD3D   its day, not a choice, and is ignored. A build must exist as a Nightlies "-Linux" package
          (async forks map to gplasync with DXVK_ASYNC=1).
  env     Variables a recipe may set (droiddeck-recipe's allowlist), on agreement.

"Agree" means: at least MIN_SOURCES independent uploads (distinct upload tokens, or devices for
uploads without one) choose the same value, AGREEMENT of those that chose anything for it, and for
FEX at least FEX_SHARE of the game's sources. tools/recipes/overrides.json is applied last: games
tested by hand, kept, changed or blocked.

Output: the app's recipe file (version 2: per game, a list of variants, each optionally limited to
GPU families) and a Markdown report of what was kept and why.
"""
import argparse
import collections
import json
import os
import re
import runpy
import sys
import time
import urllib.request
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[1]
RECIPE = runpy.run_path(str(REPO / "tools/linuxfs/overlay/usr/local/bin/droiddeck-recipe"))

MIN_SOURCES = 3
AGREEMENT = 0.75
FEX_SHARE = 0.5
DEFAULT_SHARE = 0.10
NIGHTLIES = "https://api.github.com/repos/The412Banner/Nightlies/releases/tags/%s"
RELEASES = ("Dxvk-Linux", "Dxvk-gplasync-Linux", "Vkd3d-proton-Linux")

# GameHub's FEX settings, FEX's own default for each (DroidDeck's Default preset), and the variable.
FEX_FIELDS = {
    "TSOEnabled": (True, "FEX_TSOENABLED"),
    "VectorTSOEnabled": (False, "FEX_VECTORTSOENABLED"),
    "MemcpySetTSOEnabled": (False, "FEX_MEMCPYSETTSOENABLED"),
    "HalfBarrierTSOEnabled": (True, "FEX_HALFBARRIERTSOENABLED"),
    "X87ReducedPrecision": (False, "FEX_X87REDUCEDPRECISION"),
    "Multiblock": (True, "FEX_MULTIBLOCK"),
    "SMCChecks": ("mtrack", "FEX_SMCCHECKS"),
    "HideHypervisorBit": (False, "FEX_HIDEHYPERVISORBIT"),
    "SmallTSCScale": (True, "FEX_SMALLTSCSCALE"),
    "VolatileMetadata": (True, "FEX_VOLATILEMETADATA"),
    "MonoHacks": (True, "FEX_MONOHACKS"),
}
SMC_VALUES = ("none", "mtrack", "full")
# GameHub's per-game recommendation: not the player's choice.
RECOMMENDED_PROFILE = "game_recommend_id"
FAMILIES = {"6": "A6XX", "8": "A8XX"}
A7XX_LOW = {"710", "720", "722"}
STAMP = re.compile(r"-(\d{10})\.json$")


# ------------------------------------------------------------------ reading

def json_field(value):
    if isinstance(value, dict):
        return value
    if isinstance(value, str) and value.startswith("{"):
        try:
            return json.loads(value)
        except ValueError:
            return None
    return None


def family(soc):
    """DroidDeck's GPU family (GpuInfo.Family) for an uploaded SoC string, or None."""
    match = re.search(r"Adreno[^0-9]*(\d)(\d\d)", soc or "")
    if not match:
        return None
    model = match.group(1) + match.group(2)
    if match.group(1) == "7":
        return "A7XX_LOW" if model in A7XX_LOW else "A7XX"
    return FAMILIES.get(match.group(1))


class Upload:
    """One uploaded config, reduced to what the converter reads."""

    def __init__(self, name, data):
        meta = data.get("meta") or {}
        settings = data.get("settings") or {}
        stamp = STAMP.search(name)
        self.time = int(stamp.group(1)) if stamp else 0
        # Uploads from before meta existed are named <game>-<maker>-<model>-<time>.json.
        self.device = meta.get("device") or (name.rsplit("-", 2)[-2] if name.count("-") >= 2 else name)
        device = self.device
        self.source = meta.get("upload_token") or "device:" + str(device)
        self.family = family(meta.get("soc"))
        container = json_field(settings.get("pc_ls_CONTAINER_LIST")) or {}
        self.arm64x = "arm64x" in (container.get("name") or "")
        self.dxvk = (json_field(settings.get("pc_ls_DXVK")) or {}).get("name")
        self.vkd3d = (json_field(settings.get("pc_ls_VK3k")) or {}).get("name")
        self.fex = None
        for key, value in settings.items():
            if "TRANSLATOR_CONFIG_APPLYING_FEX" in key:
                profile = json_field(value)
                if profile is not None:
                    self.fex = profile
        self.env = {}
        for part in re.split(r"\s+", (settings.get("pc_ls_environment_variable") or "").strip()):
            if "=" in part:
                name_, _, val = part.partition("=")
                self.env[name_] = val.strip('"')

    def fex_chosen(self):
        """The FEX values the player chose, or {} when the profile is GameHub's recommendation."""
        profile = self.fex
        if not profile or not self.arm64x:
            return {}
        changed = {k.rsplit(".", 1)[-1].replace("Setting", "") for k in (profile.get("userChangeSettingMap") or {})}
        if profile.get("id") == RECOMMENDED_PROFILE and not changed:
            return {}
        return {field: profile[field] for field in FEX_FIELDS if field in profile}


def read_uploads(bannerhub):
    """{folder: [Upload]} from a bannerhub-game-configs checkout."""
    result = {}
    configs = Path(bannerhub) / "configs"
    for folder in sorted(os.listdir(configs)):
        if not (configs / folder).is_dir():
            continue
        uploads = []
        for name in sorted(os.listdir(configs / folder)):
            if not name.endswith(".json"):
                continue
            try:
                with open(configs / folder / name, encoding="utf-8") as source:
                    uploads.append(Upload(name, json.load(source)))
            except (OSError, ValueError, AttributeError):
                continue
        if uploads:
            result[folder] = uploads
    return result


def app_ids(bannerlator):
    """{folder: (appid, steam name)} from a bannerlator-game-configs checkout; Steam apps only."""
    base = Path(bannerlator)
    with open(base / "games_steam.json", encoding="utf-8") as source:
        resolved = json.load(source)
    with open(base / "steam_aliases.json", encoding="utf-8") as source:
        aliases = json.load(source)
    names = {}
    try:
        with open(base / "games_canonical.json", encoding="utf-8") as source:
            names = {k: v.get("name") for k, v in json.load(source).items()}
    except (OSError, ValueError):
        pass
    result = {}
    for folder, entry in resolved.items():
        if isinstance(entry, dict) and isinstance(entry.get("appid"), int):
            result[folder] = str(entry["appid"])
    for folder, appid in aliases.items():
        if isinstance(appid, int):
            result[folder] = str(appid)
    return {folder: (appid, names.get(appid) or (resolved.get(folder) or {}).get("steam_name") or folder)
            for folder, appid in result.items()}


# ------------------------------------------------------------------ packages

def fetch_catalog():
    catalog = {}
    for tag in RELEASES:
        with urllib.request.urlopen(NIGHTLIES % tag, timeout=60) as response:
            catalog[tag] = [asset["name"] for asset in json.load(response)["assets"]]
    return catalog


VERSION = re.compile(r"(\d+)\.(\d+)(?:\.(\d+))?")
EXOTIC = ("mali", "sarek", "pre-reg", "special", "stripped", "low-latency", "binsem", "tfix", "tilting", "gamesir", "fix")


def parse_build(name):
    """(version tuple, async) of a GameHub DXVK/VKD3D build name, or None for builds left alone."""
    low = (name or "").lower()
    match = VERSION.search(low)
    if not match or any(tag in low for tag in EXOTIC):
        return None
    version = (int(match.group(1)), int(match.group(2)), int(match.group(3) or 0))
    return version, "async" in low


def catalog_versions(files, comp):
    """{version: file} for the plain builds of one release (exotic variants left out)."""
    result = {}
    for file in files:
        low = file.lower()
        if comp == "vkd3d" and not low.startswith("vkd3d-proton-"):
            continue
        parsed = parse_build(low.replace("-linux.wcp", ""))
        if parsed:
            result[parsed[0]] = file
    return result


def package_for(comp, build, catalog):
    """(package ref, extra env, label) for a GameHub build, or None when no Linux build matches."""
    parsed = parse_build(build)
    if parsed is None:
        return None
    version, is_async = parsed
    if comp == "dxvk":
        release = "Dxvk-gplasync-Linux" if is_async else "Dxvk-Linux"
    else:
        release = "Vkd3d-proton-Linux"
    versions = catalog_versions(catalog.get(release, []), comp)
    file = versions.get(version)
    if file is None:
        same = [v for v in versions if v[:2] == version[:2]]
        if not same:
            return None
        file = versions[max(same)]
    env = {"DXVK_ASYNC": "1"} if comp == "dxvk" and is_async else {}
    return {"file": file, "release": release}, env, file.replace("-linux.wcp", "")


# ------------------------------------------------------------------ agreement

def agreed(votes, sources_total=None, share=0.0):
    """The value independent sources agree on, and how many chose it; None when they do not."""
    if not votes:
        return None
    counts = collections.Counter(votes.values())
    value, count = counts.most_common(1)[0]
    if count < MIN_SOURCES or count / len(votes) < AGREEMENT:
        return None
    if sources_total and count / sources_total < share:
        return None
    return value, count


def latest_vote(uploads, pick):
    """{source: value} with each source's most recent choice (None choices left out)."""
    votes = {}
    for upload in sorted(uploads, key=lambda u: u.time):
        value = pick(upload)
        if value is not None:
            votes[upload.source] = value
    return votes


def fex_env(field, value):
    default, name = FEX_FIELDS[field]
    if field == "SMCChecks":
        return (name, value) if value in SMC_VALUES and value != default else None
    if not isinstance(value, bool) or value == default:
        return None
    return name, "1" if value else "0"


def game_variants(uploads, catalog, defaults):
    """The variants (any-GPU FEX/env first, then one per family with packages) and their evidence."""
    variants, evidence = [], []
    sources = {u.source for u in uploads if u.arm64x}
    fex = {}
    for field in FEX_FIELDS:
        votes = latest_vote(uploads, lambda u: u.fex_chosen().get(field))
        found = agreed({k: json.dumps(v) for k, v in votes.items()}, len(sources), FEX_SHARE)
        if found:
            env = fex_env(field, json.loads(found[0]))
            if env:
                fex[env[0]] = env[1]
                evidence.append("%s=%s (%d of %d sources)" % (env[0], env[1], found[1], len(votes)))
    env_votes = collections.defaultdict(dict)
    for upload in sorted(uploads, key=lambda u: u.time):
        for name, value in upload.env.items():
            env_votes[name][upload.source] = value
    for name, votes in sorted(env_votes.items()):
        found = agreed(votes, len({u.source for u in uploads}), FEX_SHARE)
        if found and RECIPE["env_allowed"](name, found[0]):
            fex[name] = found[0]
            evidence.append("%s=%s (%d of %d sources)" % (name, found[0], found[1], len(votes)))
    if fex:
        variants.append({"env": fex})
    for fam in ("A6XX", "A7XX_LOW", "A7XX", "A8XX"):
        on = [u for u in uploads if u.family == fam and u.arm64x]
        variant, notes = {}, []
        for comp, pick in (("dxvk", lambda u: u.dxvk), ("vkd3d", lambda u: u.vkd3d)):
            found = agreed(latest_vote(on, pick))
            if not found or found[0] in defaults.get((fam, comp), ()):
                continue
            mapped = package_for(comp, found[0], catalog)
            if mapped is None:
                continue
            ref, env, label = mapped
            variant[comp] = ref
            if env:
                variant.setdefault("env", {}).update(env)
            notes.append("%s %s from %s (%d sources)" % (comp.upper(), label, found[0], found[1]))
        if variant:
            variant["gpu"] = [fam]
            variants.append(variant)
            evidence.extend("%s: %s" % (fam, note) for note in notes)
    return variants, evidence


def default_builds(uploads):
    """{(family, comp): builds} carried by at least DEFAULT_SHARE of uploads on that family."""
    counts = collections.defaultdict(collections.Counter)
    for upload in uploads:
        if upload.family:
            counts[upload.family, "dxvk"][upload.dxvk] += 1
            counts[upload.family, "vkd3d"][upload.vkd3d] += 1
    return {key: {b for b, n in c.items() if n / sum(c.values()) >= DEFAULT_SHARE} for key, c in counts.items()}


# ------------------------------------------------------------------ output

def apply_overrides(games, overrides):
    for appid, entry in overrides.get("games", {}).items():
        if entry is None or entry.get("blocked"):
            games.pop(appid, None)
        else:
            games[appid] = entry
    return games


def build(bannerhub, bannerlator, catalog, overrides=None, now=None):
    folders = read_uploads(bannerhub)
    ids = app_ids(bannerlator)
    by_app = collections.defaultdict(list)
    names = {}
    for folder, uploads in folders.items():
        if folder in ids:
            appid, name = ids[folder]
            by_app[appid].extend(uploads)
            names[appid] = name
    defaults = default_builds([u for uploads in folders.values() for u in uploads])
    games, report = {}, []
    for appid in sorted(by_app, key=int):
        uploads = by_app[appid]
        variants, evidence = game_variants(uploads, catalog, defaults)
        if not variants:
            continue
        latest = time.strftime("%Y-%m", time.gmtime(max(u.time for u in uploads))) if any(u.time for u in uploads) else ""
        note = "community: %d uploads, %d sources, latest %s" % (len(uploads), len({u.source for u in uploads}), latest)
        for variant in variants:
            variant["note"] = note
        games[appid] = {"name": names[appid], "variants": variants}
        report.append((appid, names[appid], len(uploads), evidence))
    apply_overrides(games, overrides or {})
    library = {
        "version": 2,
        "generated": time.strftime("%Y-%m-%d", time.gmtime(now or time.time())),
        "sources": ["https://github.com/The412Banner/bannerhub-game-configs",
                    "https://github.com/The412Banner/bannerlator-game-configs"],
        "games": games,
    }
    stats = {"uploads": sum(map(len, folders.values())), "folders": len(folders),
             "steam_apps": len(by_app), "games": len(games), "defaults": {"%s %s" % k: sorted(filter(None, v)) for k, v in defaults.items()}}
    return library, report, stats


def write_report(path, report, stats, overrides):
    lines = ["# DroidDeck Proton (Auto) community library", "",
             "Generated by `tools/recipes/convert.py`; do not edit by hand. See",
             "[game-environment.md](game-environment.md#droiddeck-proton-auto) for the rules.", "",
             "- Uploads read: %d in %d folders" % (stats["uploads"], stats["folders"]),
             "- Resolved to Steam apps: %d" % stats["steam_apps"],
             "- Games with a recipe: %d" % stats["games"], "",
             "Builds treated as app defaults (ignored): " + "; ".join(
                 "%s: %s" % (k, ", ".join(v)) for k, v in sorted(stats["defaults"].items()) if v), "",
             "| App | Game | Uploads | Kept |", "| --- | --- | --- | --- |"]
    for appid, name, count, evidence in report:
        lines.append("| %s | %s | %d | %s |" % (appid, name.replace("|", "/"), count, "<br>".join(evidence)))
    tested = overrides.get("games", {})
    if tested:
        lines += ["", "## Overrides (tools/recipes/overrides.json)", ""]
        for appid, entry in sorted(tested.items()):
            lines.append("- %s: %s" % (appid, "blocked" if not entry or entry.get("blocked") else entry.get("name", "replaced")))
    Path(path).write_text("\n".join(lines) + "\n", encoding="utf-8")


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("--bannerhub", required=True)
    parser.add_argument("--bannerlator", required=True)
    parser.add_argument("--catalog", help="Nightlies asset names as JSON {release: [files]}; fetched when omitted")
    parser.add_argument("--overrides", default=str(HERE / "overrides.json"))
    parser.add_argument("--out", default=str(REPO / "app/src/main/assets/recipes/default.json"))
    parser.add_argument("--report", default=str(REPO / "docs/development/dd-proton-library.md"))
    args = parser.parse_args(argv)
    if args.catalog:
        with open(args.catalog, encoding="utf-8") as source:
            catalog = json.load(source)
    else:
        catalog = fetch_catalog()
    overrides = {}
    if os.path.isfile(args.overrides):
        with open(args.overrides, encoding="utf-8") as source:
            overrides = json.load(source)
    library, report, stats = build(args.bannerhub, args.bannerlator, catalog, overrides)
    with open(args.out, "w", encoding="utf-8") as target:
        json.dump(library, target, indent=1, sort_keys=True, ensure_ascii=False)
        target.write("\n")
    write_report(args.report, report, stats, overrides)
    print("convert: %d uploads, %d Steam apps, %d games with a recipe -> %s" % (
        stats["uploads"], stats["steam_apps"], stats["games"], args.out))
    return 0


if __name__ == "__main__":
    sys.exit(main())
