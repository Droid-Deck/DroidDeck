#!/usr/bin/env python3
from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import subprocess
import sys
import urllib.parse
from datetime import datetime, timedelta, timezone
from pathlib import Path

from make_index import check_entry
from make_pack import FLAVORS, HERE, PATCHES, REVOKED, SERIES, PackError, patch_digest, read_revoked, sha256_file, slug

FLAVORS_FILE = HERE / "flavors.json"
ARCHIVE = re.compile(r"\.tar\.(gz|xz)$")
ASSIGNMENT = re.compile(r"(?:export\s+|override\s+)?([A-Za-z_][A-Za-z0-9_]*)\s*(?:\?|\+|!|:{1,3})?=\s*(.*)")
REFERENCE = re.compile(r"\$[({]([A-Za-z_][A-Za-z0-9_]*)[)}]")
IMAGE = re.compile(r"[A-Za-z0-9][A-Za-z0-9.-]*(?::[0-9]+)?(?:/[A-Za-z0-9._-]+)+(?::[A-Za-z0-9._-]+)?(?:@sha256:[0-9a-f]{64})?")
REPO = re.compile(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")
CLONES = ("shallow", "treeless")
PACK_REV = re.compile(r"-r([0-9]+)$")
ARTIFACT_PREFIX = "droiddeck-esync-pack-"
FAILED = ".failed"
RETRY_AFTER = timedelta(days=1)
VDF_TOKEN = re.compile(r'"((?:[^"\\]|\\.)*)"|([{}])')
APP_HEADER = re.compile(r"^AppID : ([0-9]+),", re.M)
ANSI = re.compile(r"\x1b\[[0-9;]*[A-Za-z]")


def gh(*args: str) -> subprocess.CompletedProcess:
    try:
        return subprocess.run(["gh", *args], capture_output=True, text=True, check=False)
    except FileNotFoundError as e:
        raise PackError("the GitHub CLI (gh) is not installed") from e


def json_values(text: str) -> list:
    decoder = json.JSONDecoder()
    values = []
    position = 0
    while True:
        while position < len(text) and text[position].isspace():
            position += 1
        if position == len(text):
            return values
        value, position = decoder.raw_decode(text, position)
        values.append(value)


def gh_api(path: str, paginate: bool = False, missing_ok: bool = False):
    result = gh("api", *(["--paginate"] if paginate else []), path)
    if result.returncode != 0:
        if missing_ok and "HTTP 404" in result.stderr:
            return None
        raise PackError(f"gh api {path}: {result.stderr.strip() or f'exit {result.returncode}'}")
    try:
        values = json_values(result.stdout)
    except ValueError as e:
        raise PackError(f"gh api {path}: {e}") from e
    if len(values) == 1:
        return values[0]
    if values and all(isinstance(value, list) for value in values):
        return [item for value in values for item in value]
    raise PackError(f"gh api {path}: unexpected output")


def quote(value: str) -> str:
    return urllib.parse.quote(value, safe="")


def load_flavors(path: Path) -> dict:
    try:
        data = json.loads(path.read_text())
    except (OSError, ValueError) as e:
        raise PackError(f"cannot read {path}: {e}") from e

    def fail(why: str):
        raise PackError(f"{path}: {why}")

    def compiles(pattern: str, where: str):
        try:
            re.compile(pattern)
        except re.error as e:
            fail(f"{where}: {pattern!r} is not a regular expression: {e}")

    if not isinstance(data, dict) or data.get("format") != 1 or not isinstance(data.get("flavors"), dict):
        fail("is not a format 1 flavor list")
    image = data.get("sdk_image")
    if not isinstance(image, dict) or not all(isinstance(image.get(key), str) and image[key] for key in ("file", "variable", "match")):
        fail("sdk_image needs file, variable and match")
    for name, cfg in data["flavors"].items():
        if name not in FLAVORS:
            fail(f"unknown flavor {name!r}")
        if not isinstance(cfg, dict) or not isinstance(cfg.get("repo"), str) or not REPO.fullmatch(cfg["repo"]):
            fail(f"{name}: repo must be owner/name")
        for key in ("keep", "rev"):
            if type(cfg.get(key)) is not int or cfg[key] < 1:
                fail(f"{name}: {key} must be a positive number")
        if not isinstance(cfg.get("series"), str) or not SERIES.fullmatch(cfg["series"]):
            fail(f"{name}: series is not a patch series name")
        if cfg.get("profile") not in ("valve", "cachyos"):
            fail(f"{name}: profile must be valve or cachyos")
        if type(cfg.get("source_match")) is not bool:
            fail(f"{name}: source_match must be a boolean")
        build_dir = cfg.get("build_dir")
        if not isinstance(build_dir, str) or not re.fullmatch(r"(?:/[A-Za-z0-9._-]+)+", build_dir) or ".." in build_dir.split("/"):
            fail(f"{name}: build_dir must be the absolute path the release was built at")
        wine = cfg.get("wine")
        submodules = wine.get("submodules") if isinstance(wine, dict) else None
        if not isinstance(submodules, list) or "wine" not in submodules \
                or not all(isinstance(item, str) and re.fullmatch(r"[A-Za-z0-9._-]+(?:/[A-Za-z0-9._-]+)*", item) for item in submodules):
            fail(f"{name}: wine.submodules must list the submodules to check out, wine among them")
        if wine.get("clone") not in CLONES:
            fail(f"{name}: wine.clone must be one of {', '.join(CLONES)}")
        prep = wine.get("prep")
        if not isinstance(prep, str) or (prep and not re.fullmatch(r"[A-Za-z0-9._-]+(?:/[A-Za-z0-9._-]+)*", prep)) or ".." in prep.split("/"):
            fail(f"{name}: wine.prep must be a path inside the checkout or empty")
        if cfg.get("list") == "tags":
            families = cfg.get("families")
            if not isinstance(families, dict) or not families:
                fail(f"{name}: families must name at least one tag family")
            for family, settings in families.items():
                if not isinstance(settings, dict) or not isinstance(settings.get("tag"), str) \
                        or not isinstance(settings.get("prefix"), str) or not re.fullmatch(r"[A-Za-z0-9._-]+", settings["prefix"]) \
                        or not isinstance(settings.get("depot_app", ""), str):
                    fail(f"{name}: family {family} needs a tag prefix and a tag pattern")
                compiles(settings["tag"], f"{name}: family {family}")
            if not isinstance(cfg.get("version_token"), str) or "{tag}" not in cfg["version_token"]:
                fail(f"{name}: version_token must contain {{tag}}")
        elif cfg.get("list") == "releases":
            if not isinstance(cfg.get("tag"), str) or not isinstance(cfg.get("assets"), list) or not cfg["assets"] \
                    or not all(isinstance(pattern, str) for pattern in cfg["assets"]):
                fail(f"{name}: releases need a tag pattern and asset patterns")
            for pattern in (cfg["tag"], *cfg["assets"]):
                compiles(pattern, name)
        else:
            fail(f"{name}: list must be tags or releases")
    return data


def natural_key(text: str) -> list[tuple]:
    return [(0, int(part), "") if part[0] in "0123456789" else (1, 0, part) for part in re.findall(r"[0-9]+|[^0-9]+", text)]


def candidate(flavor: str, cfg: dict, tag: str, asset: str = "", token: str = "", depot_app: str = "") -> dict:
    return {
        "key": slug(f"{flavor}-{ARCHIVE.sub('', asset) if asset else tag}"),
        "flavor": flavor,
        "repo": cfg["repo"],
        "tag": tag,
        "asset": asset,
        "token": token,
        "depot_app": depot_app,
        "rev": cfg["rev"],
        "series": cfg["series"],
        "profile": cfg["profile"],
        "source_match": cfg["source_match"],
        "submodules": " ".join(cfg["wine"]["submodules"]),
        "clone": cfg["wine"]["clone"],
        "prep": cfg["wine"]["prep"],
        "build_dir": cfg["build_dir"],
    }


def tag_candidates(flavor: str, cfg: dict, ref: str) -> list[dict]:
    found = []
    for family, settings in sorted(cfg["families"].items()):
        if ref and not ref.startswith(settings["prefix"]):
            continue
        refs = gh_api(f"repos/{cfg['repo']}/git/matching-refs/tags/{quote(settings['prefix'])}", paginate=True)
        if not isinstance(refs, list):
            raise PackError(f"{cfg['repo']}: the tag list is not a list")
        pattern = re.compile(settings["tag"])
        names = set()
        for item in refs:
            name = item.get("ref", "") if isinstance(item, dict) else ""
            if isinstance(name, str) and name.startswith("refs/tags/") and pattern.search(name[len("refs/tags/"):]):
                names.add(name[len("refs/tags/"):])
        ordered = sorted(names, key=natural_key, reverse=True)
        chosen = [name for name in ordered if name == ref] if ref else ordered[:cfg["keep"]]
        for name in chosen:
            found.append(candidate(flavor, cfg, name, token=cfg["version_token"].format(tag=name), depot_app=settings.get("depot_app", "")))
    return found


def pick_assets(assets: list, patterns: list[re.Pattern]) -> list[str]:
    chosen = {}
    for asset in assets:
        name = asset.get("name") if isinstance(asset, dict) else None
        if not isinstance(name, str) or not any(pattern.search(name) for pattern in patterns):
            continue
        stem = ARCHIVE.sub("", name)
        if stem not in chosen or name.endswith(".tar.xz"):
            chosen[stem] = name
    return sorted(chosen.values())


def release_candidates(flavor: str, cfg: dict, ref: str) -> list[dict]:
    repo = cfg["repo"]
    if ref:
        release = gh_api(f"repos/{repo}/releases/tags/{quote(ref)}", missing_ok=True)
        releases = [release] if release else []
    else:
        releases = gh_api(f"repos/{repo}/releases?per_page=100")
    if not isinstance(releases, list):
        raise PackError(f"{repo}: the release list is not a list")
    pattern = re.compile(cfg["tag"])
    patterns = [re.compile(item) for item in cfg["assets"]]
    usable = []
    for release in releases:
        tag = release.get("tag_name") if isinstance(release, dict) else None
        if not isinstance(tag, str) or release.get("draft") or not pattern.search(tag):
            continue
        assets = pick_assets(release.get("assets") or [], patterns)
        if assets:
            usable.append((release.get("published_at") or release.get("created_at") or "", natural_key(tag), tag, assets))
    usable.sort(reverse=True)
    if not ref:
        usable = usable[:cfg["keep"]]
    return [candidate(flavor, cfg, tag, asset=asset) for _, _, tag, assets in usable for asset in assets]


def built(index: dict) -> dict[tuple[str, str, str], int]:
    revs = {}
    packs = index.get("packs") if isinstance(index, dict) else None
    for pack in packs if isinstance(packs, list) else []:
        if not isinstance(pack, dict) or not isinstance(pack.get("source"), dict):
            continue
        rev = pack.get("rev") if type(pack.get("rev")) is int else 0
        key = (pack.get("flavor"), pack["source"].get("ref"), pack["source"].get("asset", ""))
        revs[key] = max(revs.get(key, 0), rev)
    return revs


def revoked_build(entry: dict, revoked: set[str]) -> bool:
    prefixes = {f"{entry['flavor']}-{slug(name)}-" for name in (entry["token"], entry["tag"]) if name}
    suffix = f"-r{entry['rev']}"
    return any(ident.endswith(suffix) and ident.startswith(prefix) for ident in revoked for prefix in prefixes)


def chosen_flavors(flavors: dict, selected: str) -> list[str]:
    return sorted(flavors["flavors"]) if selected == "all" else [selected]


def failure_marker(entry: dict, digests: dict[str, str] | None = None) -> str:
    series = entry["series"]
    if not SERIES.fullmatch(series):
        raise PackError(f"{series!r} is not a patch series name")
    digest = digests.get(series) if digests is not None else None
    if digest is None:
        digest = patch_digest(PATCHES / series)
        if digests is not None:
            digests[series] = digest
    return f"{entry['key']}-r{entry['rev']}-{digest[:12]}{FAILED}"


def discover(flavors: dict, selected: str, ref: str, index: dict, revoked: set[str],
             failed: frozenset[str] = frozenset()) -> list[dict]:
    names = chosen_flavors(flavors, selected)
    done = built(index)
    digests = {}
    entries = []
    seen = set()
    matched = False
    for flavor in names:
        cfg = flavors["flavors"].get(flavor)
        if cfg is None:
            raise PackError(f"flavors.json has no {flavor}")
        found = tag_candidates(flavor, cfg, ref) if cfg["list"] == "tags" else release_candidates(flavor, cfg, ref)
        matched = matched or bool(found)
        for entry in found:
            if entry["key"] in seen:
                continue
            seen.add(entry["key"])
            label = f"{flavor} {entry['tag']}{' ' + entry['asset'] if entry['asset'] else ''}"
            if revoked_build(entry, revoked):
                print(f"discover: {label}: revoked at rev {entry['rev']}", file=sys.stderr)
            elif done.get((flavor, entry["tag"], entry["asset"]), 0) >= entry["rev"]:
                print(f"discover: {label}: in the index at rev {entry['rev']}", file=sys.stderr)
            elif not ref and failure_marker(entry, digests) in failed:
                print(f"discover: {label}: failed at rev {entry['rev']} with this patch series in the last "
                      f"{RETRY_AFTER.days} days; skipped (bump rev, change the series or build the tag by name)", file=sys.stderr)
            else:
                print(f"discover: {label}: to build", file=sys.stderr)
                entries.append(entry)
    if ref and not matched:
        raise PackError(f"no {'' if selected == 'all' else selected + ' '}build is tagged {ref}")
    return entries


def vdf(text: str) -> dict:
    stack = [{}]
    key = None
    for match in VDF_TOKEN.finditer(text):
        word, brace = match.group(1), match.group(2)
        if brace == "{":
            if key is None:
                raise PackError("app info: a block has no name")
            child = {}
            stack[-1][key] = child
            stack.append(child)
            key = None
        elif brace == "}":
            if len(stack) == 1:
                raise PackError("app info: unbalanced braces")
            stack.pop()
            key = None
        elif key is None:
            key = word
        else:
            stack[-1][key] = word
            key = None
    if len(stack) != 1:
        raise PackError("app info: unbalanced braces")
    return stack[0]


def steam_builds(text: str, apps: list[str]) -> dict[str, dict]:
    found = {}
    text = ANSI.sub("", text)
    starts = [(m.start(), m.group(1)) for m in APP_HEADER.finditer(text)]
    for number, (start, app) in enumerate(starts):
        end = starts[number + 1][0] if number + 1 < len(starts) else len(text)
        if app not in apps:
            continue
        body = text[text.find("\n", start) + 1:end]
        info = vdf(body).get(app)
        depots = info.get("depots") if isinstance(info, dict) else None
        public = depots.get("branches", {}).get("public") if isinstance(depots, dict) else None
        if not isinstance(public, dict) or not str(public.get("buildid", "")).isdigit():
            continue
        manifests = sorted(depot["manifests"]["public"]["gid"] for name, depot in depots.items()
                           if name.isdigit() and isinstance(depot, dict) and isinstance(depot.get("manifests"), dict)
                           and isinstance(depot["manifests"].get("public"), dict) and depot["manifests"]["public"].get("gid"))
        found[app] = {"buildid": public["buildid"], "built": public.get("timebuildupdated", ""), "manifests": manifests}
    return found


def steam_apps(flavors: dict) -> list[str]:
    return sorted({family["depot_app"] for cfg in flavors["flavors"].values() if cfg["list"] == "tags"
                   for family in cfg["families"].values() if family.get("depot_app")})


def watch(flavors: dict, previous: dict, steam: dict[str, dict] | None) -> tuple[dict, list[str], list[str]]:
    state = {}
    changed = []
    notes = []
    for flavor in sorted(flavors["flavors"]):
        cfg = flavors["flavors"][flavor]
        found = tag_candidates(flavor, cfg, "") if cfg["list"] == "tags" else release_candidates(flavor, cfg, "")
        builds = sorted(f"{entry['tag']} {entry['asset']}".strip() for entry in found)
        ships = {}
        if cfg["list"] == "tags" and steam is not None:
            for family in cfg["families"].values():
                app = family.get("depot_app")
                if app and app in steam:
                    ships[app] = steam[app]
        old = previous.get(flavor) if isinstance(previous.get(flavor), dict) else {}
        if steam is None and isinstance(old.get("steam"), dict):
            ships = old["steam"]
        mark = {"builds": builds, "rev": cfg["rev"], "series": cfg["series"], "patch": patch_digest(PATCHES / cfg["series"])}
        fingerprint = hashlib.sha256(json.dumps(mark, sort_keys=True).encode()).hexdigest()
        state[flavor] = {**mark, "fingerprint": fingerprint, "steam": ships}
        if old.get("fingerprint") != fingerprint:
            changed.append(flavor)
            fresh = sorted(set(builds) - set(old.get("builds") or []))
            notes.append(f"{flavor}: {', '.join(fresh) if fresh else 'the patch series or rev changed'}")
        for app, ship in sorted(ships.items()):
            before = (old.get("steam") or {}).get(app) if isinstance(old.get("steam"), dict) else None
            if isinstance(before, dict) and before.get("buildid") != ship.get("buildid"):
                notes.append(f"{flavor}: Steam app {app} moved from build {before.get('buildid')} to {ship.get('buildid')}")
                if flavor not in changed:
                    changed.append(flavor)
    return state, changed, notes


def asset_text(repo: str, asset_id, what: str) -> str:
    result = gh("api", "-H", "Accept: application/octet-stream", f"repos/{repo}/releases/assets/{asset_id}")
    if result.returncode != 0:
        raise PackError(f"cannot download {what}: {result.stderr.strip()}")
    return result.stdout


def release_index(repo: str, tag: str) -> dict:
    release = gh_api(f"repos/{repo}/releases/tags/{quote(tag)}", missing_ok=True)
    if release is None:
        return {}
    for asset in release.get("assets") or []:
        if isinstance(asset, dict) and asset.get("name") == "index.json":
            text = asset_text(repo, asset["id"], f"the index of {repo}")
            try:
                return json.loads(text)
            except ValueError as e:
                raise PackError(f"the index of {repo} is not JSON: {e}") from e
    return {}


def published(repo: str, flavors: dict, selected: str, index: dict) -> list[dict]:
    packs = index.get("packs") if isinstance(index, dict) else None
    listed = {pack.get("id") for pack in packs if isinstance(pack, dict)} if isinstance(packs, list) else set()
    found = []
    for flavor in chosen_flavors(flavors, selected):
        cfg = flavors["flavors"].get(flavor)
        tag = f"droiddeck-esync-{flavor}"
        release = gh_api(f"repos/{repo}/releases/tags/{quote(tag)}", missing_ok=True) if cfg else None
        if not isinstance(release, dict) or type(release.get("id")) is not int:
            continue
        assets = gh_api(f"repos/{repo}/releases/{release['id']}/assets?per_page=100", paginate=True)
        if not isinstance(assets, list):
            raise PackError(f"{repo} {tag}: the asset list is not a list")
        for asset in assets:
            name = asset.get("name") if isinstance(asset, dict) else None
            if not isinstance(name, str) or not name.endswith(".json"):
                continue
            ident = name[:-len(".json")]
            rev = PACK_REV.search(ident)
            if ident in listed or not rev or int(rev.group(1)) < cfg["rev"]:
                continue
            try:
                entry = json.loads(asset_text(repo, asset.get("id"), f"{tag} {name}"))
            except ValueError:
                entry = None
            if not isinstance(entry, dict) or entry.get("id") != ident or entry.get("flavor") != flavor:
                print(f"discover: {tag} {name} is not a pack entry", file=sys.stderr)
                continue
            print(f"discover: {tag} {name}: published, not in the index yet", file=sys.stderr)
            found.append(entry)
    return found


def parse_time(value) -> datetime | None:
    if not isinstance(value, str):
        return None
    try:
        moment = datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError:
        return None
    return moment if moment.tzinfo else moment.replace(tzinfo=timezone.utc)


def recent_failures(repo: str, flavors: dict, selected: str, now: datetime) -> frozenset[str]:
    found = set()
    for flavor in chosen_flavors(flavors, selected):
        tag = f"droiddeck-esync-{flavor}"
        release = gh_api(f"repos/{repo}/releases/tags/{quote(tag)}", missing_ok=True) if flavor in flavors["flavors"] else None
        if not isinstance(release, dict) or type(release.get("id")) is not int:
            continue
        assets = gh_api(f"repos/{repo}/releases/{release['id']}/assets?per_page=100", paginate=True)
        if not isinstance(assets, list):
            raise PackError(f"{repo} {tag}: the asset list is not a list")
        for asset in assets:
            name = asset.get("name") if isinstance(asset, dict) else None
            if not isinstance(name, str) or not name.endswith(FAILED):
                continue
            moment = parse_time(asset.get("updated_at")) or parse_time(asset.get("created_at"))
            if moment is not None and now - moment < RETRY_AFTER:
                found.add(name)
    return frozenset(found)


def failed_builds(matrix: dict, directory: Path) -> list[tuple[str, str]]:
    rows = matrix.get("include") if isinstance(matrix, dict) else None
    if not isinstance(rows, list) or not all(isinstance(row, dict) and isinstance(row.get("key"), str) for row in rows):
        raise PackError("the build matrix has no include list")
    made = {row["key"] for row in rows if (directory / f"{ARTIFACT_PREFIX}{row['key']}").is_dir()}
    digests = {}
    return [(row["flavor"], failure_marker(row, digests)) for row in rows if row["key"] not in made]


def artifact_packs(matrix: dict, directory: Path) -> list[tuple[str, Path, str]]:
    rows = matrix.get("include") if isinstance(matrix, dict) else None
    if not isinstance(rows, list) or not all(isinstance(row, dict) and isinstance(row.get("key"), str) for row in rows):
        raise PackError("the build matrix has no include list")
    builds = {row["key"]: row for row in rows}
    found = []
    for folder in sorted(directory.iterdir()) if directory.is_dir() else []:
        row = builds.get(folder.name[len(ARTIFACT_PREFIX):]) if folder.name.startswith(ARTIFACT_PREFIX) else None
        if row is None or folder.is_symlink() or not folder.is_dir():
            raise PackError(f"{folder.name} is not the artifact of a build in this run")
        names = sorted(path.name for path in folder.iterdir())
        entries = [name for name in names if name.endswith(".json")]
        if len(entries) != 1 or names != sorted([entries[0], entries[0][:-len(".json")] + ".tzst"]):
            raise PackError(f"{folder.name} must hold exactly <id>.json and <id>.tzst")
        ident = entries[0][:-len(".json")]
        entry_path, archive = folder / entries[0], folder / f"{ident}.tzst"
        if entry_path.is_symlink() or archive.is_symlink() or not entry_path.is_file() or not archive.is_file():
            raise PackError(f"{folder.name} holds links or special files")
        try:
            data = json.loads(entry_path.read_text())
        except ValueError as e:
            raise PackError(f"{entry_path}: {e}") from e
        entry = check_entry(data, str(entry_path))
        source = data.get("source") if isinstance(data.get("source"), dict) else {}
        if entry["id"] != ident or entry["flavor"] != row["flavor"] or entry["rev"] != row["rev"] \
                or source.get("ref") != row["tag"] or source.get("asset", "") != row["asset"]:
            raise PackError(f"{entry_path} is not the pack of {row['flavor']} {row['tag']} at rev {row['rev']}")
        if archive.stat().st_size != entry["asset"]["size"] or sha256_file(archive) != entry["asset"]["sha256"]:
            raise PackError(f"{archive} is not the asset its entry describes")
        found.append((row["flavor"], folder, ident))
    return found


def emit(entries: list[dict], unindexed: int = 0) -> None:
    matrix = json.dumps({"include": entries}, sort_keys=True, separators=(",", ":"))
    path = os.environ.get("GITHUB_OUTPUT")
    if path:
        with open(path, "a") as stream:
            stream.write(f"matrix={matrix}\ncount={len(entries)}\nunindexed={unindexed}\n")
    print(matrix)


def make_variables(text: str) -> tuple[dict[str, str], list[tuple[str, str]]]:
    variables = {}
    assignments = []
    for raw in text.splitlines():
        match = ASSIGNMENT.fullmatch(raw.split("#", 1)[0].strip())
        if match:
            name, value = match.group(1), match.group(2).strip()
            variables.setdefault(name, value)
            assignments.append((name, value))
    return variables, assignments


def expand(value: str, variables: dict[str, str], depth: int = 0) -> str:
    if depth > 16:
        raise PackError("Makefile variables nest too deeply")

    def lookup(match: re.Match) -> str:
        if match.group(1) not in variables:
            raise PackError(f"$({match.group(1)}) is not set in the Makefile")
        return expand(variables[match.group(1)], variables, depth + 1)

    return REFERENCE.sub(lookup, value)


def sdk_image(text: str, variable: str = "STEAMRT_IMAGE", match: str = "arm64-llvm") -> str:
    variables, assignments = make_variables(text)
    problem = None
    for name, value in assignments:
        if not name.startswith(variable):
            continue
        try:
            image = expand(value, variables)
        except PackError as e:
            problem = problem or e
            continue
        if match not in image:
            continue
        if not IMAGE.fullmatch(image):
            raise PackError(f"{name} = {image!r} is not an image reference")
        return image
    raise problem or PackError(f"no {variable} assignment names an {match} image")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Find the Proton builds that have no droiddeck-esync pack yet, as a GitHub Actions matrix.")
    parser.add_argument("--flavors", default=str(FLAVORS_FILE))
    commands = parser.add_subparsers(dest="command", required=True)
    matrix = commands.add_parser("matrix")
    matrix.add_argument("--flavor", default="all", choices=("all", *FLAVORS))
    matrix.add_argument("--ref", default="")
    matrix.add_argument("--index", default="")
    matrix.add_argument("--index-release", default="")
    matrix.add_argument("--repo", default=os.environ.get("GITHUB_REPOSITORY", "Droid-Deck/DroidDeck"))
    matrix.add_argument("--revoked", default=str(REVOKED))
    image = commands.add_parser("sdk-image")
    image.add_argument("makefile")
    artifacts = commands.add_parser("packs")
    artifacts.add_argument("--matrix", required=True)
    artifacts.add_argument("directory")
    failures = commands.add_parser("failures")
    failures.add_argument("--matrix", required=True)
    failures.add_argument("directory")
    commands.add_parser("steam-apps")
    watching = commands.add_parser("watch")
    watching.add_argument("--state", default="")
    watching.add_argument("--steam-info", default="")
    watching.add_argument("--out", required=True)
    args = parser.parse_args(argv)
    try:
        flavors = load_flavors(Path(args.flavors))
        if args.command == "sdk-image":
            settings = flavors["sdk_image"]
            print(sdk_image(Path(args.makefile).read_text(), settings["variable"], settings["match"]))
            return 0
        if args.command == "packs":
            for flavor, folder, ident in artifact_packs(json.loads(args.matrix), Path(args.directory)):
                print(f"{flavor}\t{folder}\t{ident}")
            return 0
        if args.command == "failures":
            for flavor, marker in failed_builds(json.loads(args.matrix), Path(args.directory)):
                print(f"{flavor}\t{marker}")
            return 0
        if args.command == "steam-apps":
            print(" ".join(steam_apps(flavors)))
            return 0
        if args.command == "watch":
            previous = {}
            if args.state and Path(args.state).is_file():
                try:
                    previous = json.loads(Path(args.state).read_text())
                except ValueError:
                    print("discover: the previous watch state is not JSON; every flavor counts as changed", file=sys.stderr)
                previous = previous.get("flavors", {}) if isinstance(previous, dict) and previous.get("format") == 1 else {}
            steam = None
            if args.steam_info:
                try:
                    steam = steam_builds(Path(args.steam_info).read_text(errors="replace"), steam_apps(flavors))
                except (OSError, PackError) as e:
                    print(f"discover: Steam app info unusable ({e}); watching the source tags only", file=sys.stderr)
                if steam is not None and len(steam) != len(steam_apps(flavors)):
                    print(f"discover: Steam app info covers {sorted(steam)} of {steam_apps(flavors)}", file=sys.stderr)
            state, changed, notes = watch(flavors, previous, steam)
            Path(args.out).write_text(json.dumps({"format": 1, "flavors": state}, indent=1, sort_keys=True) + "\n")
            for note in notes:
                print(f"discover: {note}", file=sys.stderr)
            path = os.environ.get("GITHUB_OUTPUT")
            if path:
                with open(path, "a") as stream:
                    stream.write(f"changed={' '.join(changed)}\n")
            print(" ".join(changed))
            return 0
        if args.index and args.index_release:
            raise PackError("pass --index or --index-release, not both")
        failed = frozenset()
        unindexed = 0
        if args.index:
            path = Path(args.index)
            index = json.loads(path.read_text()) if path.exists() else {}
        elif args.index_release:
            if not REPO.fullmatch(args.repo):
                raise PackError(f"{args.repo!r} is not owner/name")
            index = release_index(args.repo, args.index_release)
            listed = index.get("packs") if isinstance(index, dict) and isinstance(index.get("packs"), list) else []
            waiting = published(args.repo, flavors, args.flavor, index)
            unindexed = len(waiting)
            index = {"packs": [*listed, *waiting]}
            failed = recent_failures(args.repo, flavors, args.flavor, datetime.now(timezone.utc))
        else:
            index = {}
        revoked = read_revoked(Path(args.revoked)) if args.revoked else set()
        emit(discover(flavors, args.flavor, args.ref.strip(), index, revoked, failed), unindexed)
    except (PackError, OSError, ValueError) as e:
        print(f"discover: {e}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
