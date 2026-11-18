#!/usr/bin/env python3

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
MERGED_PR = re.compile(r"\[PR #([1-9][0-9]*)\]\(")


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

    def get_all(self, path: str, limit: int = 1000) -> list:
        items = []
        page = 1
        sep = "&" if "?" in path else "?"
        while len(items) < limit:
            batch = self.get(f"{path}{sep}per_page=100&page={page}")
            if not batch:
                break
            items.extend(batch)
            if len(batch) < 100:
                break
            page += 1
        return items

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


def iso_millis(value: str | None) -> int:
    if not value:
        return 0
    return int(dt.datetime.fromisoformat(value.replace("Z", "+00:00")).timestamp() * 1000)


def published_millis(release: dict, prefer_updated: bool = False) -> int:
    return iso_millis(
        (release.get("updated_at") if prefer_updated else None)
        or release.get("published_at")
        or release.get("created_at")
    )


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


def asset_for_suffix(assets: list[dict], suffix: str, all_suffixes: list[str], commit: str = ""):
    others = [s for s in all_suffixes if s and s != suffix]
    # A test build's release briefly holds the previous push's APKs too; the one named for this commit wins.
    current = f"-{commit[:7].lower()}{suffix}.apk" if len(commit) >= 7 else None
    if current:
        assets = sorted(assets, key=lambda a: not (a.get("name") or "").lower().endswith(current))
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
    extra: dict | None = None,
):
    suffixes = [suffix for _, suffix in variants]
    apks = {}
    for package, suffix in variants:
        asset = asset_for_suffix(release.get("assets") or [], suffix, suffixes, commit)
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
        **(extra or {}),
        "apks": apks,
    }


def complete_apks(preview: dict, variants: list[tuple[str, str]]) -> bool:
    apks = preview.get("apks") or {}
    return set(apks) == {package for package, _ in variants} and all(
        SHA256.fullmatch((apk.get("sha256") or "").lower()) and SHA256.fullmatch((apk.get("signerSha256") or "").lower())
        for apk in apks.values()
    )


def newest_complete(ordered: list[dict], variants: list[tuple[str, str]]) -> dict | None:
    """The Preview to offer and to judge merges by: the newest one whose APKs have all been uploaded."""
    return next((p for p in ordered if complete_apks(p, variants)), None)


def preview_history_entry(release: dict, preview: dict, variants: list[tuple[str, str]]):
    tag = release.get("tag_name") or ""
    tag_match = MAIN_TAG.fullmatch(tag)
    commit = (preview.get("commit") or "").lower()
    if (
        release.get("draft")
        or not release.get("prerelease")
        or not tag_match
        or not re.fullmatch(r"[0-9a-f]{40}", commit)
        or not commit.startswith(tag_match.group(1))
        or not complete_apks(preview, variants)
    ):
        return None
    return {
        "commit": commit,
        "title": preview["title"],
        "summary": preview["summary"][:400],
        "publishedAt": preview["publishedAt"],
    }


def recent_previews(entries: list[dict], limit: int = 10) -> list[dict]:
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
    apps = [
        r for r in releases
        if not r.get("draft") and not r.get("prerelease")
        and any(a.get("name") == f"DroidDeck-{r.get('tag_name')}.apk" for a in r.get("assets") or [])
    ]
    return max(apps, key=lambda r: r.get("published_at") or r.get("created_at") or "", default=None)


def merged_pr(release: dict) -> int:
    ps = paragraphs(release.get("body") or "")
    match = MERGED_PR.search(ps[0]) if ps else None
    return int(match.group(1)) if match else 0


class Cache:
    """Commit facts carried over from the published catalog, so a run only asks GitHub about new builds."""

    def __init__(self, previous: dict | None):
        self.commits: dict[str, dict] = {}
        for entry in (previous or {}).get("previews") or []:
            commit = (entry.get("commit") or "").lower()
            if commit and entry.get("committedAt"):
                self.commits[commit] = {"author": entry.get("author") or "", "committedAt": entry["committedAt"]}

    def commit(self, gh: GitHub, repo: str, sha: str) -> dict:
        sha = sha.lower()
        if sha not in self.commits:
            try:
                obj = gh.get(f"/repos/{repo}/commits/{q(sha)}")
                self.commits[sha] = {
                    "author": (obj.get("author") or {}).get("login") or "",
                    "committedAt": iso_millis(((obj.get("commit") or {}).get("committer") or {}).get("date")),
                }
            except ApiError:
                self.commits[sha] = {"author": "", "committedAt": 0}
        return self.commits[sha]


MERGED_GRACE_DAYS = 3


def in_preview(source: GitHub, repo: str, pull: dict, preview_commit: str | None, now_millis: int | None = None) -> bool:
    """Whether the newest Preview build was built from a main that already holds the PR's merge.

    A merge that main's build skips (documentation only) never reaches a Preview build, so after
    MERGED_GRACE_DAYS it counts as carried anyway.
    """
    merge = pull.get("merge_commit_sha") or ""
    merged_at = iso_millis(pull.get("merged_at"))
    if not merged_at or not merge:
        return False
    now = now_millis if now_millis is not None else int(time.time() * 1000)
    if now - merged_at > MERGED_GRACE_DAYS * 86_400_000:
        return True
    if not preview_commit:
        return False
    try:
        status = source.get(f"/repos/{repo}/compare/{q(merge)}...{q(preview_commit)}").get("status")
    except ApiError:
        return False
    return status in ("ahead", "identical")


def keeps_test_build(pull: dict) -> bool:
    """An open PR keeps its test build; a merged one keeps it until a Preview build carries the merge."""
    if pull.get("draft") or (pull.get("base") or {}).get("ref") != "main":
        return False
    return pull.get("state") == "open" or bool(pull.get("merged_at"))


def unique_previews(previews: list[dict]) -> list[dict]:
    ordered = sorted(previews, key=lambda r: (r.get("committedAt") or r["publishedAt"], r["publishedAt"]), reverse=True)
    result = []
    seen = set()
    for preview in ordered:
        commit = preview["commit"].lower()
        if commit in seen:
            continue
        seen.add(commit)
        result.append(preview)
    return result


def build_catalog(source: GitHub, ci: GitHub, source_repo: str, ci_repo: str, previous: dict | None = None) -> dict:
    cache = Cache(previous)
    stable_release = stable_app_release(source.get_all(f"/repos/{source_repo}/releases", limit=300))

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
            extra={"committedAt": cache.commit(source, source_repo, stable_commit)["committedAt"]},
        )

    main_code, _, main_variants, main_signer = source_meta(source, source_repo, "main")
    releases = ci.get_all(f"/repos/{ci_repo}/releases")
    previews = []
    preview_history = []
    tests = []
    merged_pulls: dict[int, dict | None] = {}
    for release in releases:
        if release.get("draft"):
            continue
        tag = release.get("tag_name") or ""
        main_match = MAIN_TAG.fullmatch(tag)
        if main_match:
            commit = main_commit(release)
            if not commit:
                continue
            code_text = release_body_value(release.get("body") or "", "VersionCode")
            if code_text and code_text.isdigit():
                code, variants, signer = int(code_text), main_variants, main_signer
            else:
                try:
                    code, _, variants, signer = source_meta(source, source_repo, commit)
                except ApiError:
                    continue
            facts = cache.commit(source, source_repo, commit)
            title, summary = ci_description(release)
            preview = release_entry(
                release, commit, 0, None, code, variants, signer, title, summary,
                extra={"mergedPr": merged_pr(release), "author": facts["author"], "committedAt": facts["committedAt"]},
            )
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
        if not keeps_test_build(pull):
            continue
        commit = release_body_value(body, "Commit")
        if not commit or not re.fullmatch(r"[0-9a-f]{7,40}", commit):
            continue
        version_code_text = release_body_value(body, "VersionCode")
        if version_code_text and version_code_text.isdigit():
            version_code = int(version_code_text)
        else:
            base_ref = release_body_value(body, "Base") or pull["base"]["sha"]
            try:
                version_code = source_meta(source, source_repo, base_ref)[0]
            except ApiError:
                continue
            try:
                changed = source.get(f"/repos/{source_repo}/pulls/{pr}/files?per_page=100")
                if any(item.get("filename") == "app/build.gradle" for item in changed):
                    head_repo = pull["head"]["repo"]["full_name"]
                    version_code = parse_gradle(repo_file(source, head_repo, "app/build.gradle", pull["head"]["sha"]))[0]
            except (ApiError, KeyError):
                pass
        title, summary = ci_description(release)
        merged_pulls[pr] = pull if pull.get("state") != "open" else None
        tests.append(
            release_entry(
                release,
                commit,
                pr,
                None,
                version_code,
                main_variants,
                main_signer,
                title,
                summary,
                prefer_updated=True,
                extra={
                    "author": (pull.get("user") or {}).get("login") or "",
                    "headCommit": (pull.get("head") or {}).get("sha") or "",
                    "builtAt": max(
                        (iso_millis(a.get("updated_at") or a.get("created_at"))
                         for a in release.get("assets") or [] if (a.get("name") or "").endswith(".apk")),
                        default=0,
                    ),
                },
            )
        )

    ordered = unique_previews(previews)
    usable = newest_complete(ordered, main_variants)
    newest = usable["commit"] if usable else None
    tests = [t for t in tests if not (merged_pulls.get(t["pr"]) and in_preview(source, source_repo, merged_pulls[t["pr"]], newest))]
    tests.sort(key=lambda r: r["publishedAt"], reverse=True)
    return {
        "schema": 1,
        "sourceRepo": source_repo,
        "ciRepo": ci_repo,
        "generatedAt": int(time.time() * 1000),
        "mainVersionCode": main_code,
        "stable": stable,
        "preview": usable,
        "recentPreviews": recent_previews(preview_history),
        "previews": ordered,
        "tests": tests,
    }


def current_file(ci: GitHub, repo: str, path: str, branch: str = "main"):
    try:
        return ci.get(f"/repos/{repo}/contents/{q(path)}?ref={q(branch)}")
    except ApiError as e:
        if e.status == 404:
            return None
        raise


def previous_catalog(old: dict | None) -> dict | None:
    try:
        return json.loads(base64.b64decode(old["content"])) if old and old.get("content") else None
    except Exception:
        return None


def publish_catalog(source: GitHub, ci: GitHub, source_repo: str, ci_repo: str, path: str, branch: str = "main"):
    for attempt in range(4):
        old = current_file(ci, ci_repo, path, branch)
        catalog = build_catalog(source, ci, source_repo, ci_repo, previous_catalog(old))
        content = (json.dumps(catalog, separators=(",", ":")) + "\n").encode()
        body = {
            "message": "ci: refresh DroidDeck update catalog",
            "content": base64.b64encode(content).decode(),
            "branch": branch,
        }
        if old:
            body["sha"] = old["sha"]
            try:
                old_json = dict(previous_catalog(old) or {})
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
            print(f"published https://raw.githubusercontent.com/{ci_repo}/{branch}/{path}")
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
    parser.add_argument("--branch", default="main")
    parser.add_argument("--dry-run", action="store_true")
    args = parser.parse_args()

    source = GitHub(os.environ.get("SOURCE_GITHUB_TOKEN", ""))
    ci = GitHub(os.environ.get("CI_GITHUB_TOKEN", os.environ.get("SOURCE_GITHUB_TOKEN", "")))
    if args.dry_run:
        print(json.dumps(build_catalog(source, ci, args.source_repo, args.ci_repo), indent=2))
    else:
        if not os.environ.get("CI_GITHUB_TOKEN"):
            raise SystemExit("CI_GITHUB_TOKEN is required to publish")
        publish_catalog(source, ci, args.source_repo, args.ci_repo, args.path, args.branch)


if __name__ == "__main__":
    main()
