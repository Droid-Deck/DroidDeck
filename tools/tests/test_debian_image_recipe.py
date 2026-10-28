import hashlib
import os
from pathlib import Path
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[2]
RECIPE = ROOT / "tools/components/recipes/debian-image.sh"


class DebianImageRecipeTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.bin = self.root / "bin"
        self.bin.mkdir()
        self.log = self.root / "calls"
        recipe = self.root / "tools/components/recipes"
        recipe.mkdir(parents=True)
        (recipe / "debian-image.sh").write_text(RECIPE.read_text())
        linuxfs = self.root / "tools/linuxfs"
        linuxfs.mkdir(parents=True)
        self._command("gh", """\
{
  printf 'gh'
  for arg in "$@"; do printf '\t%s' "$arg"; done
  printf '\n'
} >> "$TEST_LOG"
exit 0
""")
        self._command("sha256sum", """\
test "$CHECKSUM_OK" = 1
""")
        self._command("zstd", """\
printf image
""")
        self._command("docker", """\
{
  printf 'docker'
  for arg in "$@"; do printf '\t%s' "$arg"; done
  printf '\n'
} >> "$TEST_LOG"
if [ "$1" = load ]; then cat >/dev/null; fi
exit 0
""")
        self._command("sleep", "exit 0\n")
        self._command("rm", "exit 0\n")

    def _command(self, name, body):
        path = self.bin / name
        path.write_text("#!/usr/bin/env bash\nset -eu\n" + body)
        path.chmod(0o755)

    def _run(self, repo=None, checksum_ok=True):
        lines = [
            "DEBIAN_IMAGE_TAG=ci-debian",
            "DEBIAN_IMAGE_ASSET=debian.tar.zst",
            "DEBIAN_IMAGE_SHA256=" + hashlib.sha256(b"image").hexdigest(),
        ]
        if repo:
            lines.insert(0, "DEBIAN_IMAGE_REPO=" + repo)
        (self.root / "tools/linuxfs/debian-image.env").write_text("\n".join(lines) + "\n")
        environment = {
            **os.environ,
            "PATH": str(self.bin) + os.pathsep + os.environ["PATH"],
            "TEST_LOG": str(self.log),
            "CHECKSUM_OK": "1" if checksum_ok else "0",
            "GITHUB_REPOSITORY": "wallentx/DroidDeck",
        }
        environment.pop("DEBIAN_IMAGE_REPO", None)
        return subprocess.run(["bash", "tools/components/recipes/debian-image.sh"],
                              cwd=self.root, env=environment, text=True, capture_output=True)

    def test_uses_producer_repository_or_current_repository_default(self):
        for producer, expected in (("Droid-Deck/DroidDeck", "Droid-Deck/DroidDeck"),
                                   (None, "wallentx/DroidDeck")):
            with self.subTest(producer=producer):
                self.log.unlink(missing_ok=True)
                result = self._run(producer)
                self.assertEqual(result.returncode, 0, result.stderr)
                calls = self.log.read_text()
                self.assertIn("\t-R\t" + expected + "\t-p\tdebian.tar.zst", calls)
                self.assertIn("docker\tload", calls)
                self.assertNotIn("docker\tpull", calls)

    def test_checksum_failure_uses_registry_fallback_without_loading_unverified_image(self):
        result = self._run("Droid-Deck/DroidDeck", checksum_ok=False)
        self.assertEqual(result.returncode, 0, result.stderr)
        calls = self.log.read_text()
        self.assertIn("\t-R\tDroid-Deck/DroidDeck\t-p\tdebian.tar.zst", calls)
        self.assertIn("docker\tpull\tpublic.ecr.aws/docker/library/debian:bullseye", calls)
        self.assertNotIn("docker\tload", calls)


if __name__ == "__main__":
    unittest.main()
