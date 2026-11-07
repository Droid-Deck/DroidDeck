import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import textwrap
import unittest


ROOT = Path(__file__).resolve().parents[2]


class SystemVulkanPublishTest(unittest.TestCase):
    def publish(self, prerelease, exists=False):
        workflow = (ROOT / ".github/workflows/system-vulkan-components.yml").read_text()
        step = workflow.split("      - name: Publish immutable component release\n", 1)[1]
        lines = step.split("        run: |\n", 1)[1].splitlines()
        script = []
        for line in lines:
            if line and not line.startswith("          "):
                break
            script.append(line)
        with tempfile.TemporaryDirectory() as temporary:
            workspace = Path(temporary)
            output = workspace / "out/system-vulkan"
            output.mkdir(parents=True)
            for name in ("system-vulkan-mali.tar.zst", "droiddeck-component-system-vulkan-mali.json",
                         "component-bootstrap.json", "SHA256SUMS"):
                (output / name).write_text("fixture")
            binary = workspace / "bin"
            binary.mkdir()
            capture = workspace / "calls.jsonl"
            gh = binary / "gh"
            gh.write_text(f"#!{sys.executable}\n" + textwrap.dedent("""\
                import json, os, sys
                with open(os.environ['GH_CAPTURE'], 'a') as log:
                    log.write(json.dumps(sys.argv[1:]) + '\\n')
                if sys.argv[1:3] == ['release', 'view']:
                    sys.exit(0 if os.environ['GH_RELEASE_EXISTS'] == 'true' else 1)
                """))
            gh.chmod(0o755)
            environment = {**os.environ, "PATH": str(binary) + os.pathsep + os.environ["PATH"],
                           "GH_CAPTURE": str(capture), "GH_RELEASE_EXISTS": str(exists).lower(),
                           "GH_TOKEN": "fixture", "GITHUB_REPOSITORY": "example/components",
                           "GITHUB_SHA": "a" * 40, "COMPONENT_VARIANT": "mali",
                           "COMPONENT_VERSION": "v0.1.0", "RELEASE_TAG": "system-vulkan-mali-v0.1.0",
                           "COMPONENT_PRERELEASE": str(prerelease).lower()}
            result = subprocess.run(["bash", "-c", textwrap.dedent("\n".join(script))],
                                    cwd=workspace, env=environment, capture_output=True, text=True)
            calls = [json.loads(line) for line in capture.read_text().splitlines()]
            return result, calls

    def test_github_prerelease_flag_controls_both_channels(self):
        for prerelease in (False, True):
            with self.subTest(prerelease=prerelease):
                result, calls = self.publish(prerelease)
                self.assertEqual(0, result.returncode, result.stderr)
                create = next(call for call in calls if call[:2] == ["release", "create"])
                self.assertEqual("system-vulkan-mali-v0.1.0", create[2])
                self.assertIn(f"--prerelease={str(prerelease).lower()}", create)
                self.assertIn("--latest=false", create)
                self.assertIn("--verify-tag", create)
                self.assertEqual("a" * 40, create[create.index("--target") + 1])
                self.assertIn("out/system-vulkan/system-vulkan-mali.tar.zst", create)
                self.assertFalse(any("powervr" in arg or "xclipse" in arg for arg in create))
                ref = next(call for call in calls if call[0] == "api")
                self.assertIn("ref=refs/tags/system-vulkan-mali-v0.1.0", ref)

    def test_existing_component_release_is_never_replaced(self):
        result, calls = self.publish(True, exists=True)
        self.assertNotEqual(0, result.returncode)
        self.assertIn("already exists", result.stdout)
        self.assertEqual([["release", "view", "system-vulkan-mali-v0.1.0"]], calls)


if __name__ == "__main__":
    unittest.main()
