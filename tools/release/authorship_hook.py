#!/usr/bin/env python3
import os
from pathlib import Path
import subprocess
import sys

from check_authorship import COAUTHOR, check_revision, is_bot


def main():
    hook, *args = sys.argv[1:]
    payload = sys.stdin.buffer.read() if hook == "pre-push" else None
    if hook == "pre-push":
        checked = set()
        for line in payload.decode().splitlines():
            local_ref, local_sha, _, _ = line.split()
            if local_sha == "0" * 40 or local_sha in checked:
                continue
            checked.add(local_sha)
            _, errors = check_revision(local_sha)
            if errors:
                print("\n".join(errors), file=sys.stderr)
                return 1
    else:
        identity = subprocess.check_output(["git", "var", "GIT_AUTHOR_IDENT"], text=True)
        if is_bot(identity):
            print("Bot commit author is forbidden: " + identity.strip(), file=sys.stderr)
            return 1
        message = Path(args[0])
        original = message.read_text(encoding="utf-8")
        cleaned = "".join(line for line in original.splitlines(keepends=True)
                          if not (COAUTHOR.match(line) and is_bot(line)))
        if cleaned != original:
            message.write_text(cleaned, encoding="utf-8")
            print("Removed bot co-author trailers; human credits preserved.", file=sys.stderr)
    legacy = Path(__file__).with_name("legacy-" + hook)
    if legacy.exists() and os.access(legacy, os.X_OK):
        return subprocess.run([str(legacy), *args], input=payload).returncode
    return 0


if __name__ == "__main__":
    sys.exit(main())
