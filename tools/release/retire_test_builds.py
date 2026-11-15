#!/usr/bin/env python3
"""Deletes test builds nobody can still need: a closed PR's at once, a merged PR's once a Preview build carries the merge."""

from __future__ import annotations

import argparse
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from update_catalog import ApiError, GitHub, current_file, in_preview, previous_catalog, q  # noqa: E402

TEST_TAG = re.compile(r"^pr-([1-9][0-9]*)(\.0)?$")


def retired(pull: dict, source: GitHub, repo: str, preview_commit: str | None) -> bool:
    if pull.get("state") == "open":
        return False
    if not pull.get("merged_at"):
        return True
    return in_preview(source, repo, pull, preview_commit)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--source-repo", default=os.environ.get("GITHUB_REPOSITORY", "Droid-Deck/DroidDeck"))
    parser.add_argument("--ci-repo", default="Droid-Deck/DroidDeck-CI")
    parser.add_argument("--pr", type=int, help="only this PR's test build")
    args = parser.parse_args()
    source = GitHub(os.environ.get("SOURCE_GITHUB_TOKEN", ""))
    ci = GitHub(os.environ["CI_GITHUB_TOKEN"])
    catalog = previous_catalog(current_file(ci, args.ci_repo, "catalog.json")) or {}
    preview_commit = (catalog.get("preview") or {}).get("commit")
    failed = False
    for release in ci.get_all(f"/repos/{args.ci_repo}/releases"):
        tag = release.get("tag_name") or ""
        match = TEST_TAG.fullmatch(tag)
        if not match or (args.pr and int(match.group(1)) != args.pr):
            continue
        number = int(match.group(1))
        try:
            pull = source.get(f"/repos/{args.source_repo}/pulls/{number}")
            if not retired(pull, source, args.source_repo, preview_commit):
                print(f"Keeping {tag}: PR #{number} is {'open' if pull.get('state') == 'open' else 'merged, not yet in a Preview build'}")
                continue
            ci.request("DELETE", f"/repos/{args.ci_repo}/releases/{release['id']}")
            try:
                ci.request("DELETE", f"/repos/{args.ci_repo}/git/refs/tags/{q(tag)}")
            except ApiError as e:
                if e.status not in (404, 422):
                    raise
            print(f"Deleted {tag}")
        except ApiError as e:
            print(f"{tag}: {e}", file=sys.stderr)
            failed = True
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
