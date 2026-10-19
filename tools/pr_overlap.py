#!/usr/bin/env python3
"""Comment when two open pull requests add the same file or edit overlapping lines."""

from __future__ import annotations

import json
import os
import re
import sys
import urllib.error
import urllib.request
from dataclasses import dataclass


MARKER = "<!-- droiddeck-pr-overlap -->"
HUNK = re.compile(r"^@@ -\d+(?:,\d+)? \+(\d+)(?:,(\d+))? @@")
DEPENDS = re.compile(r"^Depends on:[ \t]*(.*)$", re.MULTILINE)
API = "https://api.github.com"


@dataclass(frozen=True)
class Pull:
    number: int
    body: str


@dataclass(frozen=True)
class FileEdit:
    filename: str
    status: str
    ranges: tuple[tuple[int, int], ...] = ()
    patch_omitted: bool = False


def depends_on(body: str) -> set[int]:
    found = set()
    for line in DEPENDS.findall(body or ""):
        if line.strip() == "none":
            continue
        found.update(int(number) for number in re.findall(r"#(\d+)", line))
    return found


def parse_ranges(patch: str | None) -> tuple[tuple[int, int], ...]:
    ranges = []
    for line in (patch or "").splitlines():
        match = HUNK.match(line)
        if match is None:
            continue
        start = int(match.group(1))
        count = int(match.group(2) or "1")
        if count <= 0:
            continue
        ranges.append((start, start + count - 1))
    return tuple(ranges)


def file_reasons(left: list[FileEdit], right: list[FileEdit]) -> list[str]:
    right_by_name = {item.filename: item for item in right}
    reasons = []
    for item in left:
        other = right_by_name.get(item.filename)
        if other is None or item.status == "removed" or other.status == "removed":
            continue
        if item.status == "added" and other.status == "added":
            reasons.append(f"adds `{item.filename}`")
            continue
        reported = False
        for this_range in item.ranges:
            for that_range in other.ranges:
                if this_range[0] <= that_range[1] and that_range[0] <= this_range[1]:
                    reasons.append(
                        f"`{item.filename}` lines {this_range[0]}-{this_range[1]} "
                        f"overlap {that_range[0]}-{that_range[1]}"
                    )
                    reported = True
        if not reported and item.patch_omitted and other.patch_omitted:
            reasons.append(f"both edit `{item.filename}`, and the patch was omitted")
    return reasons


def suppressed(left: Pull, right: Pull) -> bool:
    return right.number in depends_on(left.body) or left.number in depends_on(right.body)


def render(items: list[tuple[int, list[str]]]) -> str:
    lines = [MARKER, "This pull request overlaps another open pull request.", ""]
    for number, reasons in items:
        lines.append(f"- #{number}")
        for reason in reasons[:8]:
            lines.append(f"  - {reason}")
        if len(reasons) > 8:
            lines.append(f"  - {len(reasons) - 8} more overlapping hunks")
    lines.append("")
    lines.append("List that pull request under `Depends on` if this one should follow it.")
    return "\n".join(lines) + "\n"


def plan_comments(
    pulls: list[Pull],
    files: dict[int, list[FileEdit]],
) -> dict[int, str]:
    by_number = {pull.number: pull for pull in pulls}
    numbers = sorted(by_number)
    wanted: dict[int, str] = {}
    for newer in numbers:
        items = []
        for older in numbers:
            if older >= newer:
                continue
            if suppressed(by_number[newer], by_number[older]):
                continue
            reasons = file_reasons(files.get(newer, []), files.get(older, []))
            if reasons:
                items.append((older, reasons))
        if items:
            wanted[newer] = render(items)
    return wanted


def _token() -> str:
    token = os.environ.get("GH_TOKEN") or os.environ.get("GITHUB_TOKEN")
    if not token:
        raise SystemExit("GH_TOKEN is not set")
    return token


def _request(method: str, url: str, payload: dict | None = None):
    data = None if payload is None else json.dumps(payload).encode("utf-8")
    request = urllib.request.Request(url, data=data, method=method)
    request.add_header("Authorization", f"Bearer {_token()}")
    request.add_header("Accept", "application/vnd.github+json")
    request.add_header("User-Agent", "droiddeck-pr-overlap")
    request.add_header("X-GitHub-Api-Version", "2022-11-28")
    if data is not None:
        request.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(request) as response:
            raw = response.read()
            link = response.headers.get("Link")
            parsed = json.loads(raw.decode("utf-8")) if raw else None
            return parsed, link
    except urllib.error.HTTPError as exc:
        detail = exc.read().decode("utf-8", "replace")
        raise SystemExit(f"GitHub API {exc.code} {method} {url}: {detail}") from exc


def _get_all(url: str) -> list:
    items = []
    while url:
        payload, link = _request("GET", url)
        if isinstance(payload, list):
            items.extend(payload)
        elif payload is not None:
            items.append(payload)
        url = _next_link(link)
    return items


def _next_link(header: str | None) -> str | None:
    if not header:
        return None
    for part in header.split(","):
        match = re.search(r"<([^>]+)>;\s*rel=\"next\"", part)
        if match:
            return match.group(1)
    return None


def load_pulls(repo: str) -> list[Pull]:
    rows = _get_all(f"{API}/repos/{repo}/pulls?state=open&per_page=100")
    return [Pull(number=row["number"], body=row.get("body") or "") for row in rows]


def load_files(repo: str, number: int) -> list[FileEdit]:
    rows = _get_all(f"{API}/repos/{repo}/pulls/{number}/files?per_page=100")
    edits = []
    for row in rows:
        patch = row.get("patch")
        edits.append(FileEdit(
            filename=row["filename"],
            status=row.get("status") or "",
            ranges=parse_ranges(patch),
            patch_omitted=patch is None and row.get("status") != "removed",
        ))
    return edits


def load_marker_comment(repo: str, number: int) -> int | None:
    rows = _get_all(f"{API}/repos/{repo}/issues/{number}/comments?per_page=100")
    for row in rows:
        login = ((row.get("user") or {}).get("login")) or ""
        if login == "github-actions[bot]" and MARKER in (row.get("body") or ""):
            return row["id"]
    return None


def apply_plan(repo: str, wanted: dict[int, str], existing: dict[int, int | None]) -> None:
    for number, body in wanted.items():
        comment_id = existing.get(number)
        if comment_id is None:
            _request("POST", f"{API}/repos/{repo}/issues/{number}/comments", {"body": body})
            print(f"commented on #{number}")
        else:
            _request("PATCH", f"{API}/repos/{repo}/issues/comments/{comment_id}", {"body": body})
            print(f"updated #{number}")
    for number, comment_id in existing.items():
        if comment_id is not None and number not in wanted:
            _request("DELETE", f"{API}/repos/{repo}/issues/comments/{comment_id}")
            print(f"removed comment on #{number}")


def main() -> int:
    repo = os.environ.get("GITHUB_REPOSITORY")
    if not repo:
        print("GITHUB_REPOSITORY is not set", file=sys.stderr)
        return 2
    pulls = load_pulls(repo)
    files = {pull.number: load_files(repo, pull.number) for pull in pulls}
    wanted = plan_comments(pulls, files)
    existing = {pull.number: load_marker_comment(repo, pull.number) for pull in pulls}
    apply_plan(repo, wanted, existing)
    return 0


if __name__ == "__main__":
    sys.exit(main())
