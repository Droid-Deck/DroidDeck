#!/usr/bin/env python3
"""Build and publish the one-file update catalog consumed by DroidDeck.

The catalog is derived from GitHub releases, but the Android app never has to infer
channels, parse release notes, dereference tags, or spend unauthenticated API quota.
"""

from __future__ import annotations

import argparse
import base64
import datetime as dt
import json
import os
import re
import time
import urllib.error
import urllib.parse
import urllib.request

API = "https://api.github.com"
SHA256 = re.compile(r"^[0-9a-f]{64}$")
PR_TAG = re.compile(r"^pr-([1-9][0-9]*)$")
MAIN_TAG = re.compile(r"^main-([0-9a-f]{7,40})$")


class ApiError(RuntimeError):
    def __init__(self, status: int, message: str):
        super().__init__(f"GitHub API {status}: {message}")
        self.status = status


class GitHub:
    def __init__(self, token: str):
        self.token = token

    def request(self, method: str, path: str, body=None):
        data = None
        headers = {
            "Accept": "application/vnd.github+json",
            "User-Agent": "DroidDeck-update-catalog",
            "X-GitHub-Api-Version": "2022-11-28",
        }
        if self.token:
            headers["Authorization"] = f"Bearer {self.token}"
        if body is not None:
            data = json.dumps(body).encode()
            headers["Content-Type"] = "application/json"
        req = urllib.request.Request(API + path, data=data, headers=headers, method=method)
        try:
            with urllib.request.urlopen(req, timeout=30) as response:
                raw = response.read()
                return json.loads(raw) if raw else None
        except urllib.error.HTTPError as e:
            raw = e.read().decode(errors="replace")
            raise ApiError(e.code, raw[:500]) from e

    def get(self, path: str):
        return self.request("GET", path)

    def put(self, path: str, body):
        return self.request("PUT", path, body)


def q(value: str) -> str:
    return urllib.parse.quote(value, safe="")


def repo_file(gh: GitHub, repo: str, path: str, ref: str) -> str:
    obj = gh.get(f"/repos/{repo}/contents/{q(path)}?ref={q(ref)}")
    if obj.get("encoding") != "base64":
        raise RuntimeError(f"{repo}:{ref}:{path} was not base64 content")
    return base64.b64decode(obj["content"]).decode()


def resolve_tag(gh: GitHub, repo: str, tag: str) -> str:
    obj = gh.get(f"/repos/{repo}/git/ref/tags/{q(tag)}")["object"]
    while obj["type"] == "tag":
        obj = gh.get(f"/repos/{repo}/git/tags/{q(obj['sha'])}")["object"]
    if obj["type"] != "commit":
        raise RuntimeError(f"{repo} tag {tag} points to {obj['type']}, not a commit")
    return obj["sha"]


def parse_gradle(text: str) -> tuple[int, str | None]:
    code = re.search(r"(?m)^\s*versionCode\s+(\d+)\s*$", text)
    name = re.search(r'(?m)^\s*versionName\s+"([^"]+)"\s*$', text)
    if not code:
        raise RuntimeError("app/build.gradle has no numeric versionCode")
    return int(code.group(1)), name.group(1) if name else None


def parse_variants(text: str) -> list[tuple[str, str]]:
    out = []
    for raw in text.splitlines():
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        fields = line.split()
        if len(fields) < 3:
            raise RuntimeError(f"bad variants.txt row: {raw!r}")
        out.append((fields[1], "" if fields[2] == "-" else fields[2]))
    if not out:
        raise RuntimeError("variants.txt has no packages")
    return out


def published_millis(release: dict, prefer_updated: bool = False) -> int:
    value = (
        release.get("updated_at") if prefer_updated else None
    ) or release.get("published_at") or release.get("created_at")
    if not value:
        return 0
    return int(dt.datetime.fromisoformat(value.replace("Z", "+00:00")).timestamp() * 1000)


def paragraphs(markdown: str) -> list[str]:
    return [p.strip() for p in re.split(r"\n\s*\n", markdown.replace("\r", "")) if p.strip()]


def plain(markdown: str) -> str:
    text = re.sub(r"!\[[^]]*]\([^)]*\)", "", markdown)
    text = re.sub(r"\[([^]]*)]\([^)]*\)", r"\1", text)
    text = re.sub(r"[*_`]{1,3}", "", text)
    return re.sub(r"\s+", " ", text).strip()


def prose_lines(markdown: str) -> str:
    lines = []
    for raw in markdown.splitlines():
        match = re.match(r"^\s*[-*]\s+", raw)
        value = ("• " + plain(raw[match.end():])) if match else plain(raw)
        if value:
            lines.append(value)
    return "\n".join(lines)


def ci_description(release: dict) -> tuple[str, str]:
    ps = paragraphs(release.get("body") or "")
    head = ps[0] if ps else ""
    title = ""
    if "): " in head:
        title = head.split("): ", 1)[1]
        title = re.sub(r" \(merged[^)]*\)$", "", title)
    title = plain(title or head or release.get("name") or release["tag_name"])
    summary = ps[1] if len(ps) > 1 else ""
    if summary.startswith("Signed build") or summary.startswith("Source:"):
        summary = ""
    return title, prose_lines(summary)


def stable_description(release: dict) -> tuple[str, str]:
    name = release.get("name") or release["tag_name"]
    summary = ""
    for p in paragraphs(release.get("body") or ""):
        if not (p.startswith("#") or p.startswith("![") or p.startswith("<")):
            summary = prose_lines(p)
            break
    return name, summary


def source_meta(source: GitHub, repo: str, ref: str):
    gradle = repo_file(source, repo, "app/build.gradle", ref)
    code, version = parse_gradle(gradle)
    variants = parse_variants(repo_file(source, repo, "tools/release/variants.txt", ref))
    signer = repo_file(source, repo, "keystore/release-signer.sha256", ref).strip().lower()
    if not SHA256.fullmatch(signer):
        raise RuntimeError(f"{repo}:{ref} has an invalid release signer digest")
    return code, version, variants, signer


def asset_for_suffix(assets: list[dict], suffix: str, all_suffixes: list[str]):
    others = [s for s in all_suffixes if s and s != suffix]
    for asset in assets:
        name = asset.get("name", "")
        if not name.endswith(f"{suffix}.apk"):
            continue
        if any(name.endswith(f"{other}.apk") for other in others):
            continue
        digest = (asset.get("digest") or "").lower()
        if not digest.startswith("sha256:") or not SHA256.fullmatch(digest[7:]):
            continue
        return {
            "name": name,
            "url": asset["browser_download_url"],
            "size": int(asset.get("size") or 0),
            "sha256": digest[7:],
        }
    return None


def release_entry(
    release: dict,
    commit: str,
    pr: int,
    version: str | None,
    version_code: int,
    variants: list[tuple[str, str]],
    signer: str,
    title: str,
    summary: str,
    prefer_updated: bool = False,
):
    suffixes = [suffix for _, suffix in variants]
    apks = {}
    for package, suffix in variants:
        asset = asset_for_suffix(release.get("assets") or [], suffix, suffixes)
        if not asset:
            continue
        asset.update(
            {
                "packageName": package,
                "versionCode": version_code,
                "signerSha256": signer,
            }
        )
        apks[package] = asset
    return {
        "tag": release["tag_name"],
        "title": title,
        "summary": summary,
        "commit": commit,
        "pr": pr,
        "version": version,
        "versionCode": version_code,
        "publishedAt": published_millis(release, prefer_updated),
        "url": release.get("html_url") or "",
        "apks": apks,
    }


def preview_history_entry(release: dict, preview: dict, variants: list[tuple[str, str]]):
    """Return compact history metadata for one complete, signed main build."""
    tag = release.get("tag_name") or ""
    tag_match = MAIN_TAG.fullmatch(tag)
    commit = (preview.get("commit") or "").lower()
    expected_packages = {package for package, _ in variants}
    apks = preview.get("apks") or {}
    if (
        release.get("draft")
        or not release.get("prerelease")
        or not tag_match
        or not re.fullmatch(r"[0-9a-f]{40}", commit)
        or not commit.startswith(tag_match.group(1))
        or set(apks) != expected_packages
        or any(
            not SHA256.fullmatch((apk.get("sha256") or "").lower())
            or not SHA256.fullmatch((apk.get("signerSha256") or "").lower())
            for apk in apks.values()
        )
    ):
        return None
    return {
        "commit": commit,
        "title": preview["title"],
        "summary": preview["summary"][:400],
        "publishedAt": preview["publishedAt"],
    }


def recent_previews(entries: list[dict], limit: int = 10) -> list[dict]:
    """Keep the newest distinct Preview commits, newest first."""
    result = []
    seen = set()
    for entry in sorted(entries, key=lambda item: item["publishedAt"], reverse=True):
        commit = entry["commit"].lower()
        if commit in seen:
            continue
        seen.add(commit)
        result.append({**entry, "commit": commit})
        if len(result) >= limit:
            break
    return result


def release_body_value(body: str, key: str):
    match = re.search(rf"(?mi)^\s*{re.escape(key)}:\s*`?([^\s`]+)", body or "")
    return match.group(1) if match else None


def main_commit(release: dict) -> str | None:
    body = release.get("body") or ""
    explicit = release_body_value(body, "Commit")
    if explicit and re.fullmatch(r"[0-9a-f]{7,40}", explicit):
        return explicit
    match = re.search(r"/commit/([0-9a-f]{40})", body)
    return match.group(1) if match else None


def stable_app_release(releases: list[dict]) -> dict | None:
    """The newest published app release. The repo also publishes gamescope and desktop-package
    releases that GitHub may mark latest, so an app release is one shipping DroidDeck-<tag>.apk."""
    apps = [
        r for r in releases
        if not r.get("draft") and not r.get("prerelease")
        and any(a.get("name") == f"DroidDeck-{r.get('tag_name')}.apk" for a in r.get("assets") or [])
    ]
    return max(apps, key=lambda r: r.get("published_at") or r.get("created_at") or "", default=None)


def build_catalog(source: GitHub, ci: GitHub, source_repo: str, ci_repo: str) -> dict:
    stable_release = stable_app_release(source.get(f"/repos/{source_repo}/releases?per_page=100"))

    stable = None
    if stable_release:
        stable_commit = resolve_tag(source, source_repo, stable_release["tag_name"])
        code, version_name, variants, signer = source_meta(source, source_repo, stable_commit)
        title, summary = stable_description(stable_release)
        stable = release_entry(
            stable_release,
            stable_commit,
            0,
            version_name or stable_release["tag_name"].removeprefix("v"),
            code,
            variants,
            signer,
            title,
            summary,
        )

    releases = ci.get(f"/repos/{ci_repo}/releases?per_page=100")
    previews = []
    preview_history = []
    tests = []
    for release in releases:
        if release.get("draft"):
            continue
        tag = release.get("tag_name") or ""
        main_match = MAIN_TAG.fullmatch(tag)
        if main_match:
            commit = main_commit(release)
            if not commit:
                continue
            try:
                code, _, variants, signer = source_meta(source, source_repo, commit)
            except ApiError:
                continue
            title, summary = ci_description(release)
            preview = release_entry(release, commit, 0, None, code, variants, signer, title, summary)
            previews.append(preview)
            history_entry = preview_history_entry(release, preview, variants)
            if history_entry:
                preview_history.append(history_entry)
            continue

        pr_match = PR_TAG.fullmatch(tag)
        if not pr_match:
            continue
        pr = int(pr_match.group(1))
        body = release.get("body") or ""
        if f"github.com/{source_repo}/pull/{pr}" not in body:
            continue
        try:
            pull = source.get(f"/repos/{source_repo}/pulls/{pr}")
        except ApiError:
            continue
        if pull.get("state") != "open" or pull.get("draft") or pull.get("base", {}).get("ref") != "main":
            continue
        commit = release_body_value(body, "Commit")
        if not commit or not re.fullmatch(r"[0-9a-f]{7,40}", commit):
            continue
        base_ref = release_body_value(body, "Base") or pull["base"]["sha"]
        try:
            base_code, _, variants, signer = source_meta(source, source_repo, base_ref)
        except ApiError:
            continue
        version_code_text = release_body_value(body, "VersionCode")
        if version_code_text and version_code_text.isdigit():
            version_code = int(version_code_text)
        else:
            # Legacy test releases did not record it. If the PR did not change build.gradle,
            # the base value is exact; otherwise use the PR head's value.
            version_code = base_code
            try:
                changed = source.get(f"/repos/{source_repo}/pulls/{pr}/files?per_page=100")
                if any(item.get("filename") == "app/build.gradle" for item in changed):
                    head_repo = pull["head"]["repo"]["full_name"]
                    version_code = parse_gradle(repo_file(source, head_repo, "app/build.gradle", pull["head"]["sha"]))[0]
            except (ApiError, KeyError):
                pass
        title, summary = ci_description(release)
        tests.append(
            release_entry(
                release,
                commit,
                pr,
                None,
                version_code,
                variants,
                signer,
                title,
                summary,
                prefer_updated=True,
            )
        )

    previews.sort(key=lambda r: r["publishedAt"], reverse=True)
    tests.sort(key=lambda r: r["publishedAt"], reverse=True)
    return {
        "schema": 1,
        "sourceRepo": source_repo,
        "ciRepo": ci_repo,
        "generatedAt": int(time.time() * 1000),
        "stable": stable,
        "preview": previews[0] if previews else None,
        "recentPreviews": recent_previews(preview_history),
        "tests": tests,
    }


def current_file(ci: GitHub, repo: str, path: str):
    try:
        return ci.get(f"/repos/{repo}/contents/{q(path)}?ref=main")
    except ApiError as e:
        if e.status == 404:
            return None
        raise


def publish_catalog(source: GitHub, ci: GitHub, source_repo: str, ci_repo: str, path: str):
    for attempt in range(4):
        catalog = build_catalog(source, ci, source_repo, ci_repo)
        content = (json.dumps(catalog, indent=2, sort_keys=False) + "\n").encode()
        old = current_file(ci, ci_repo, path)
        body = {
            "message": "ci: refresh DroidDeck update catalog",
            "content": base64.b64encode(content).decode(),
            "branch": "main",
        }
        if old:
            body["sha"] = old["sha"]
            try:
                old_bytes = base64.b64decode(old["content"])
                old_json = json.loads(old_bytes)
                old_json.pop("generatedAt", None)
                new_json = dict(catalog)
                new_json.pop("generatedAt", None)
                if old_json == new_json:
                    print("catalog contents are already current")
                    return catalog
            except Exception:
                pass
        try:
            ci.put(f"/repos/{ci_repo}/contents/{q(path)}", body)
            print(f"published https://raw.githubusercontent.com/{ci_repo}/main/{path}")
            return catalog
        except ApiError as e:
            if e.status not in (409, 422) or attempt == 3:
                raise
            time.sleep(1 + attempt)
    raise RuntimeError("catalog publish retry loop exhausted")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--source-repo", default="Droid-Deck/DroidDeck")
    parser.add_argument("--ci-repo", default="Droid-Deck/DroidDeck-CI")
    parser.add_argument("--path", default="catalog.json")
    parser.add_argument("--dry-run", action="store_true")
    args = parser.parse_args()

    source = GitHub(os.environ.get("SOURCE_GITHUB_TOKEN", ""))
    ci = GitHub(os.environ.get("CI_GITHUB_TOKEN", os.environ.get("SOURCE_GITHUB_TOKEN", "")))
    if args.dry_run:
        print(json.dumps(build_catalog(source, ci, args.source_repo, args.ci_repo), indent=2))
    else:
        if not os.environ.get("CI_GITHUB_TOKEN"):
            raise SystemExit("CI_GITHUB_TOKEN is required to publish")
        publish_catalog(source, ci, args.source_repo, args.ci_repo, args.path)


if __name__ == "__main__":
    main()
