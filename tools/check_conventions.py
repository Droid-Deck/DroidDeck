#!/usr/bin/env python3
"""Fail a branch that breaks DroidDeck pull request conventions."""

from __future__ import annotations

import argparse
import posixpath
import re
import subprocess
import sys
from dataclasses import dataclass
from pathlib import Path


TYPES = "build|chore|ci|docs|feat|fix|perf|refactor|revert|style|test"
SUBJECT = re.compile(
    rf"^(?:{TYPES})(?:\([A-Za-z0-9][A-Za-z0-9._/-]*\))?!?: [^ ].+$"
)
COAUTHOR = re.compile(
    r"^[ \t]*Co-authored-by:.*(Claude|Anthropic|OpenAI|ChatGPT|GPT|Copilot|Cursor|Gemini|Codex|Devin|Windsurf|Codeium|Amazon Q|Tabnine|Cody|\[bot\])",
    re.IGNORECASE | re.MULTILINE,
)
VERSION_LINE = re.compile(r"^[+-][ \t]*version(Code|Name)[ \t]")
LINK = re.compile(r"\[[^\]]*\]\(([^)]+)\)")
UNCHECKED = re.compile(r"^[-*] \[ \]", re.MULTILINE)
DEPENDS = re.compile(r"^Depends on:[ \t]*(.*)$", re.MULTILINE)
ASSISTED = re.compile(r"^AI-assisted:[ \t]*(.*)$", re.MULTILINE)
DEPENDS_VALUE = re.compile(r"none|#\d+(?:[ \t]*,[ \t]*#\d+)*\Z")

AGENT_BASENAMES = {
    "AGENTS.md",
    "CLAUDE.md",
    "GEMINI.md",
    ".cursorrules",
    "copilot-instructions.md",
}
ALLOWED_AGENT_PATHS = {
    "AGENTS.md",
    "CLAUDE.md",
    "GEMINI.md",
    ".github/copilot-instructions.md",
    ".cursor/rules/contribute.mdc",
}
CURSOR_RULE = ".cursor/rules/contribute.mdc"
IMAGE_SUFFIXES = {".png", ".jpg", ".jpeg", ".gif", ".webp", ".bmp", ".ico"}
IMAGE_PREFIXES = ("artwork/", "docs/releases/media/")
TEXT_SUFFIXES = {
    ".c", ".cc", ".cfg", ".cmake", ".comp", ".cpp", ".css", ".frag", ".glsl",
    ".gradle", ".h", ".hh", ".hpp", ".html", ".in", ".ini", ".java", ".js",
    ".json", ".kt", ".kts", ".md", ".mdc", ".mk", ".properties", ".ps1", ".py",
    ".rs", ".sh", ".svg", ".toml", ".txt", ".vert", ".xml", ".yaml", ".yml",
}


@dataclass(frozen=True)
class Failure:
    rule: str
    detail: str

    def format(self) -> str:
        return f"{self.rule}: {self.detail}"


@dataclass(frozen=True)
class Change:
    path: str
    status: str
    old_path: str | None
    patch: str
    utf8_error: str | None


def evaluate(
    changes: list[Change],
    commits: list[str],
    title: str | None,
    body: str | None,
    tree_paths: set[str],
) -> list[Failure]:
    failures: list[Failure] = []
    failures.extend(check_subjects(commits))
    failures.extend(check_title(title))
    failures.extend(check_body(body))
    failures.extend(check_coauthor(commits, title, body))
    failures.extend(check_files(changes, tree_paths))
    failures.extend(check_version(changes))
    return failures


def check_subjects(commits: list[str]) -> list[Failure]:
    failures = []
    for message in commits:
        subject = message.splitlines()[0].strip() if message.strip() else ""
        if not SUBJECT.fullmatch(subject):
            failures.append(Failure("commit-subject", subject or "(empty commit subject)"))
    return failures


def check_title(title: str | None) -> list[Failure]:
    if title is None:
        return [Failure("pr-title", "pull request title is missing")]
    text = title.strip()
    if not SUBJECT.fullmatch(text):
        return [Failure("pr-title", text or "(empty title)")]
    return []


def check_body(body: str | None) -> list[Failure]:
    if body is None:
        return [Failure("pr-template", "pull request body is not UTF-8")]
    failures = []
    if body.startswith("\ufeff"):
        failures.append(Failure(
            "pr-template",
            "pull request body has a UTF-8 BOM; write it as UTF-8 without a BOM",
        ))
        body = body[1:]
    body = body.replace("\r\n", "\n").replace("\r", "\n")
    for name in ("What", "Tested", "Checklist"):
        text = section_text(body, name)
        if text is None:
            failures.append(Failure("pr-template", f"missing ## {name}"))
        elif not text.strip():
            failures.append(Failure("pr-template", f"## {name} is empty"))
    if UNCHECKED.search(body):
        failures.append(Failure("pr-template", "checklist item is still open; mark it [x]"))
    depends = DEPENDS.findall(body)
    if len(depends) != 1 or DEPENDS_VALUE.fullmatch(depends[0].strip()) is None:
        failures.append(Failure("pr-template", "Depends on: must be #N or none"))
    assisted = ASSISTED.findall(body)
    if len(assisted) != 1 or assisted[0].strip() not in {"yes", "no"}:
        failures.append(Failure("pr-template", "AI-assisted: must be yes or no"))
    return failures


def section_text(body: str, name: str) -> str | None:
    match = re.search(rf"^## {name}\s*\n(.*?)(?=^## |\Z)", body, re.MULTILINE | re.DOTALL)
    if match is None:
        return None
    return match.group(1)


def check_coauthor(commits: list[str], title: str | None, body: str | None) -> list[Failure]:
    text = "\n".join(commits)
    if title:
        text += "\n" + title
    if body:
        text += "\n" + body
    if COAUTHOR.search(text):
        return [Failure("co-author", "AI and bot identities cannot be co-authors; remove the Co-authored-by trailer")]
    return []


def check_files(changes: list[Change], tree_paths: set[str]) -> list[Failure]:
    failures = []
    for change in changes:
        path = change.path
        name = path.rsplit("/", 1)[-1]
        if path == "docs/progress-log.md":
            failures.append(Failure("notes-file", "docs/progress-log.md is not a place for pull request notes"))
        if _new_development_file(change):
            failures.append(Failure(
                "notes-file",
                f"new file {path}; put the write-up in the pull request body",
            ))
        if change.status.startswith("D"):
            continue
        if name in AGENT_BASENAMES and path not in ALLOWED_AGENT_PATHS:
            failures.append(Failure(
                "notes-file",
                f"agent instruction file {path} is not one of the canonical entry points",
            ))
        if path.startswith(".cursor/") and path != CURSOR_RULE:
            failures.append(Failure("notes-file", f"{path} is an extra agent instruction file"))
        if path.endswith(".log") or path.endswith(".patch"):
            failures.append(Failure("notes-file", f"{path} is a scratch file"))
        suffix = Path(path).suffix.lower()
        if suffix in IMAGE_SUFFIXES and not path.startswith(IMAGE_PREFIXES):
            failures.append(Failure(
                "binary",
                f"image {path} is outside artwork/ and docs/releases/media/",
            ))
        if change.utf8_error:
            failures.append(Failure("binary", f"{path} is not UTF-8 ({change.utf8_error})"))
        failures.extend(check_links(change, tree_paths))
    return failures


def _new_development_file(change: Change) -> bool:
    if not change.path.startswith("docs/development/"):
        return False
    if change.status.startswith("A"):
        return True
    old = change.old_path or ""
    return change.status[:1] in {"R", "C"} and not old.startswith("docs/development/")


def check_links(change: Change, tree_paths: set[str]) -> list[Failure]:
    if not change.path.endswith((".md", ".mdc")) or not change.patch:
        return []
    failures = []
    for line in change.patch.splitlines():
        if not line.startswith("+") or line.startswith("+++"):
            continue
        for raw in LINK.findall(line[1:]):
            resolved = resolve_link(change.path, raw)
            if resolved is None:
                continue
            if resolved.startswith("..") or not _tree_has(tree_paths, resolved):
                failures.append(Failure(
                    "doc-link",
                    f"{change.path} links to {raw.strip()}, which is not in the tree",
                ))
    return failures


def resolve_link(file_path: str, raw: str) -> str | None:
    target = raw.strip().split()[0] if raw.strip() else ""
    if target.startswith("<") and ">" in target:
        target = target[1:target.index(">")]
    target = target.split("#", 1)[0]
    if not target or re.match(r"^[A-Za-z][A-Za-z0-9+.-]*:", target):
        return None
    directory = file_path.rpartition("/")[0]
    if target.startswith("/"):
        resolved = posixpath.normpath(target.lstrip("/"))
    else:
        resolved = posixpath.normpath(posixpath.join(directory, target))
    return resolved


def _tree_has(tree_paths: set[str], resolved: str) -> bool:
    if resolved in tree_paths:
        return True
    prefix = resolved.rstrip("/") + "/"
    return any(path.startswith(prefix) for path in tree_paths)


def check_version(changes: list[Change]) -> list[Failure]:
    gradle = next((change for change in changes if change.path == "app/build.gradle"), None)
    if gradle is None:
        return []
    version_hits = False
    other_gradle = False
    for line in gradle.patch.splitlines():
        if line.startswith(("+++", "---", "@@", "diff ", "index ", "new ", "old ", "rename ", "similarity ", "dissimilarity ")):
            continue
        if not line.startswith(("+", "-")):
            continue
        if VERSION_LINE.match(line):
            version_hits = True
        else:
            other_gradle = True
    other_files = any(change.path != "app/build.gradle" for change in changes)
    if version_hits and (other_gradle or other_files):
        return [Failure(
            "version-isolation",
            "versionCode/versionName changes must be the only change in the pull request",
        )]
    return []


def load(repo: Path, base: str, head: str) -> tuple[list[Change], list[str], set[str]]:
    merge_base = _git(repo, "merge-base", base, head).decode("utf-8").strip()
    names = _git(
        repo, "diff", "--name-status", "--find-renames", merge_base, head
    ).decode("utf-8", "surrogateescape")
    patches = _split_patches(_git(repo, "diff", "-U0", "--find-renames", merge_base, head))
    changes = []
    for status, path, old_path in _parse_name_status(names):
        patch = patches.get(path, "")
        error = None if status.startswith("D") else _utf8_error(repo, head, path)
        changes.append(Change(path, status, old_path, patch, error))
    raw_log = _git(repo, "log", "--no-merges", "--format=%B%x1e", f"{merge_base}..{head}")
    commits = [part.strip("\n") for part in raw_log.decode("utf-8", "replace").split("\x1e") if part.strip()]
    tree = _git(repo, "ls-tree", "-r", "--name-only", head).decode("utf-8", "surrogateescape").splitlines()
    return changes, commits, set(tree)


def _parse_name_status(text: str) -> list[tuple[str, str, str | None]]:
    rows = []
    for line in text.splitlines():
        if not line.strip():
            continue
        parts = line.split("\t")
        status = parts[0]
        if status[:1] in {"R", "C"} and len(parts) == 3:
            rows.append((status, parts[2], parts[1]))
        elif len(parts) >= 2:
            rows.append((status, parts[1], None))
    return rows


def _split_patches(raw: bytes) -> dict[str, str]:
    text = raw.decode("utf-8", "surrogateescape")
    found = {}
    for part in re.split(r"(?=^diff --git )", text, flags=re.MULTILINE):
        match = re.search(r"^\+\+\+ b/(.+)$", part, re.MULTILINE)
        if match and match.group(1) != "/dev/null":
            found[match.group(1)] = part
    return found


def _utf8_error(repo: Path, head: str, path: str) -> str | None:
    suffix = Path(path).suffix.lower()
    if suffix not in TEXT_SUFFIXES:
        return None
    result = subprocess.run(
        ["git", "-C", str(repo), "show", f"{head}:{path}"],
        check=False,
        capture_output=True,
    )
    if result.returncode != 0:
        return None
    try:
        result.stdout.decode("utf-8")
    except UnicodeDecodeError as exc:
        return f"byte {exc.start}"
    return None


def _git(repo: Path, *args: str) -> bytes:
    result = subprocess.run(
        ["git", "-C", str(repo), "-c", "core.quotepath=false", "-c", "i18n.logOutputEncoding=utf-8", *args],
        check=False,
        capture_output=True,
    )
    if result.returncode != 0:
        message = result.stderr.decode("utf-8", "replace").strip() or "git failed"
        raise SystemExit(message)
    return result.stdout


def read_text(path: Path) -> str | None:
    data = path.read_bytes()
    if data.startswith(b"\xef\xbb\xbf"):
        return "\ufeff" + data.decode("utf-8-sig")
    try:
        return data.decode("utf-8")
    except UnicodeDecodeError:
        return None


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base", required=True)
    parser.add_argument("--head", default="HEAD")
    parser.add_argument("--title")
    parser.add_argument("--title-file", type=Path)
    parser.add_argument("--body", required=True, type=Path)
    parser.add_argument("--repo", type=Path, default=Path("."))
    args = parser.parse_args(argv)
    if args.title is not None and args.title_file is not None:
        parser.error("pass --title or --title-file, not both")
    if args.title is not None:
        title = args.title
    elif args.title_file is not None:
        title = read_text(args.title_file)
        if title is None:
            print("pr-title: title file is not UTF-8", file=sys.stdout)
            return 1
    else:
        parser.error("pass --title or --title-file")
    body = read_text(args.body)
    repo = args.repo.resolve()
    changes, commits, tree = load(repo, args.base, args.head)
    failures = evaluate(changes, commits, title, body, tree)
    for failure in failures:
        print(failure.format())
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
