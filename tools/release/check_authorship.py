#!/usr/bin/env python3
"""Reject bot authors and bot co-author trailers without executing reviewed code."""
import argparse
import json
import re
import subprocess
import sys

PRODUCT = re.compile(
    r"(?<![a-z0-9])(?:Anthropic|OpenAI|ChatGPT|GPT(?:-?\d[\w.]*)?|Copilot|Codex|Codeium|Windsurf|"
    r"Amazon Q|Tabnine|github-actions|dependabot|renovate)(?![a-z0-9])|\[bot\]",
    re.IGNORECASE,
)
# These are also people's names, so they only count alone or followed by a product word.
ASSISTANT = r"(?:Claude|Cursor|Devin|Cody|Gemini)"
ASSISTANT_ALONE = re.compile(ASSISTANT, re.IGNORECASE)
ASSISTANT_PRODUCT = re.compile(
    ASSISTANT + r"[\s_-]+(?:Code|Opus|Sonnet|Haiku|Fable|Instant|AI|Agent|Bot|Assistant|CLI|Pro|Flash|Ultra|\d)(?![a-z])",
    re.IGNORECASE,
)
BOT_DOMAIN = re.compile(
    r"@(?:[\w-]+\.)*(?:anthropic\.com|openai\.com|cursor\.(?:com|sh)|cognition\.ai|sourcegraph\.com|"
    r"codeium\.com|tabnine\.com)$",
    re.IGNORECASE,
)
COAUTHOR = re.compile(r"^\s*Co-authored-by\s*:", re.IGNORECASE)


def is_bot(identity):
    name, _, rest = identity.partition("<")
    email = rest.partition(">")[0].strip()
    name = COAUTHOR.sub("", name).strip()
    return bool(PRODUCT.search(name) or PRODUCT.search(email) or BOT_DOMAIN.search(email)
                or ASSISTANT_ALONE.fullmatch(name) or ASSISTANT_PRODUCT.search(name))


def commit_violations(commit, raw):
    header, separator, message = raw.partition(b"\n\n")
    if not separator:
        return [f"{commit}: invalid commit object"]
    authors = [line[7:] for line in header.splitlines() if line.startswith(b"author ")]
    if len(authors) != 1:
        return [f"{commit}: expected one author"]
    identity = authors[0].decode("utf-8", errors="replace")
    errors = []
    if is_bot(identity):
        errors.append(f"{commit}: bot author: {identity}")
    for line in message.decode("utf-8", errors="replace").splitlines():
        if COAUTHOR.match(line) and is_bot(line):
            errors.append(f"{commit}: bot co-author: {line.strip()}")
    return errors


def event_violations(event):
    pr = event.get("pull_request", {})
    user = pr.get("user", {})
    errors = []
    if user.get("type") == "Bot" or is_bot(user.get("login", "")):
        errors.append("pull request: bot authorship is forbidden")
    for field in ("title", "body"):
        for line in (pr.get(field) or "").splitlines():
            if COAUTHOR.match(line) and is_bot(line):
                errors.append(f"pull request {field}: bot co-author trailer")
    return errors


def check_revision(revision):
    commits = subprocess.check_output(["git", "rev-list", revision], text=True).splitlines()
    errors = []
    with subprocess.Popen(["git", "cat-file", "--batch"], stdin=subprocess.PIPE, stdout=subprocess.PIPE) as process:
        for commit in commits:
            process.stdin.write((commit + "\n").encode("ascii"))
            process.stdin.flush()
            descriptor = process.stdout.readline().split()
            if len(descriptor) != 3 or descriptor[1] != b"commit":
                raise RuntimeError(f"Cannot read commit {commit}")
            size = int(descriptor[2])
            raw = process.stdout.read(size)
            if len(raw) != size or process.stdout.read(1) != b"\n":
                raise RuntimeError(f"Truncated commit {commit}")
            errors.extend(commit_violations(commit, raw))
        process.stdin.close()
        if process.wait() != 0:
            raise RuntimeError("git cat-file failed")
    return commits, errors


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("revision")
    parser.add_argument("--event")
    args = parser.parse_args()
    commits, errors = check_revision(args.revision)
    if args.event:
        with open(args.event, encoding="utf-8") as source:
            errors.extend(event_violations(json.load(source)))
    for error in errors:
        print(f"::error::{error}", file=sys.stderr)
    if errors:
        return 1
    print(f"Human authorship verified for {len(commits)} commits.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
