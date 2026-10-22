#!/usr/bin/env python3
"""Install shared worktree hooks while preserving existing executable hooks."""
from pathlib import Path
import shutil
import subprocess

MARKER = "# DroidDeck human authorship hook"


def install():
    common = Path(subprocess.check_output(["git", "rev-parse", "--git-common-dir"], text=True).strip()).resolve()
    configured = subprocess.run(["git", "config", "--get", "core.hooksPath"], capture_output=True, text=True)
    hooks = Path(configured.stdout.strip()).resolve() if configured.returncode == 0 else common / "hooks"
    target = common / "authorship-hooks"
    hooks.mkdir(parents=True, exist_ok=True)
    target.mkdir(parents=True, exist_ok=True)
    for name in ("check_authorship.py", "authorship_hook.py"):
        shutil.copyfile(Path(__file__).with_name(name), target / name)
    for name in ("prepare-commit-msg", "commit-msg", "pre-push"):
        path = hooks / name
        if path.exists() and MARKER not in path.read_text(errors="replace"):
            legacy = target / ("legacy-" + name)
            if legacy.exists():
                raise RuntimeError(f"Refusing to replace existing saved hook: {legacy}")
            shutil.copy2(path, legacy)
        path.write_text(
            '#!/bin/sh\n' + MARKER + '\n'
            'exec python3 "$(git rev-parse --git-common-dir)/authorship-hooks/authorship_hook.py" '
            + name + ' "$@"\n'
        )
        path.chmod(0o755)
    print(f"Human authorship hooks installed in {hooks}")


if __name__ == "__main__":
    install()
