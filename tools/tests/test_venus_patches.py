import re
from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[2]
VENUS = ROOT / "tools/venus"


def added_lines(patch, path):
    lines = []
    in_file = False
    for line in patch.splitlines():
        if line.startswith("diff --git "):
            in_file = line.endswith(" b/" + path)
        elif in_file and line.startswith("+") and not line.startswith("+++"):
            lines.append(line[1:].strip())
    return lines


class VenusPatchesTest(unittest.TestCase):
    def test_the_build_applies_every_virglrenderer_patch(self):
        script = (VENUS / "ci-build.sh").read_text()
        applied = set(re.findall(r'apply "\$REPO/tools/venus/(virglrenderer-[^"]+\.patch)"', script))
        shipped = {path.name for path in VENUS.glob("virglrenderer-*.patch")}
        self.assertEqual(shipped, applied)

    def test_pipelines_are_created_synchronously_over_vtest(self):
        patch = (VENUS / "mesa-venus-vtest.patch").read_text()
        added = added_lines(patch, "src/virtio/vulkan/vn_device.c")
        gate = added.index("if (!VN_DEBUG(VTEST))")
        self.assertEqual("vn_tls_set_async_pipeline_create();", added[gate + 1])

    def test_the_render_server_logs_every_pipeline_the_driver_refuses(self):
        patch = (VENUS / "virglrenderer-log-refused-pipeline.patch").read_text()
        added = "\n".join(added_lines(patch, "src/venus/vkr_pipeline.c"))
        for kind in ("Graphics", "Compute"):
            self.assertIn(f'vkr_log("vkCreate{kind}Pipelines failed with %d for %u pipelines"', added)


if __name__ == "__main__":
    unittest.main()
