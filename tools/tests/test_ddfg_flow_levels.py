import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[2]
CHAIN = ROOT / "app/src/main/cpp/framegen/ddfg"


class DdfgFlowLevelsTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        compiler = shutil.which(os.environ.get("CXX", "c++"))
        if compiler is None:
            raise unittest.SkipTest("A host C++ compiler is required")
        candidates = [Path("/usr/include/vulkan"), Path("/opt/homebrew/include/vulkan")]
        for key in ("ANDROID_NDK_HOME", "ANDROID_NDK_ROOT", "NDK_HOME"):
            if os.environ.get(key):
                candidates.extend(Path(os.environ[key]).glob("toolchains/llvm/prebuilt/*/sysroot/usr/include/vulkan"))
        for sdk in {Path(os.environ.get("ANDROID_HOME", "/nonexistent")),
                    Path(os.environ.get("ANDROID_SDK_ROOT", "/nonexistent")),
                    Path.home() / "Library/Android/sdk", Path.home() / "Android/Sdk"}:
            candidates.extend(sdk.glob("ndk/*/toolchains/llvm/prebuilt/*/sysroot/usr/include/vulkan"))
        headers = next((p for p in candidates if (p / "vulkan.h").is_file()), None)
        if headers is None:
            raise unittest.SkipTest("Vulkan headers from the Android NDK or system SDK are required")
        cls.temp = tempfile.TemporaryDirectory(prefix="droiddeck-ddfg-levels-")
        cls.addClassCleanup(cls.temp.cleanup)
        work = Path(cls.temp.name)
        shutil.copytree(headers, work / "include/vulkan", ignore=shutil.ignore_patterns("*.hpp"))
        if (headers.parent / "vk_video").is_dir():
            shutil.copytree(headers.parent / "vk_video", work / "include/vk_video")
        cls.binary = work / "ddfg-flow-levels"
        build = subprocess.run([
            compiler, "-std=c++17", "-O2", "-Wall", "-Wextra",
            "-Wno-missing-field-initializers", "-Wno-misleading-indentation",
            "-I" + str(work / "include"), "-I" + str(CHAIN),
            str(ROOT / "tools/tests/ddfg_flow_levels_harness.cpp"), str(CHAIN / "framegen.cpp"),
            "-o", str(cls.binary),
        ], capture_output=True, text=True, timeout=120)
        if build.returncode:
            raise RuntimeError(build.stdout + build.stderr)

    def run_case(self, mode="resources", model=4, preset=2, width=1280, height=720, generations=1, repeats=1):
        result = subprocess.run([
            str(self.binary), mode, str(model), str(preset), str(width), str(height),
            str(generations), str(repeats),
        ], capture_output=True, text=True, timeout=10)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertTrue(result.stdout.startswith("PASS "), result.stdout)
        return dict(re.findall(r"(\w+)=(\w+)", result.stdout))

    def test_each_model_and_preset_allocates_only_solved_flow_levels(self):
        for model in (3, 4):
            for preset, finest in enumerate((1, 2, 3)):
                with self.subTest(model=model, preset=preset):
                    values = self.run_case(model=model, preset=preset)
                    self.assertEqual(int(values["images"]), 32 - 2 * finest)
                    self.assertEqual(int(values["buffers"]), 18 - finest)
                    self.assertEqual(int(values["sets"]), 54 - 4 * finest)

    def test_default_preset_saves_the_exact_high_resolution_flow_payload(self):
        for width, height in ((1280, 720), (1920, 1080), (2560, 1440)):
            with self.subTest(width=width, height=height):
                values = self.run_case(width=width, height=height)
                pixels = lambda level: max(width >> level, 1) * max(height >> level, 1)
                luma_bytes = 4 * (2 * sum(pixels(l) for l in range(7)) +
                                  sum(pixels(l) for l in range(3, 7)))
                flow_bytes = 8 * (sum(pixels(l) for l in range(3, 7)) +
                                  2 * width * height + 2 * pixels(3))
                self.assertEqual(int(values["bytes"]), luma_bytes + flow_bytes)

    def test_small_and_odd_extents_keep_valid_bindings_and_dispatch_sizes(self):
        for width, height in ((1, 1), (17, 9), (135, 73)):
            for preset in range(3):
                with self.subTest(width=width, height=height, preset=preset):
                    self.run_case(width=width, height=height, preset=preset)

    def test_two_three_and_four_times_generation_keep_the_same_dispatch_schedule(self):
        for model in (3, 4):
            for preset in range(3):
                for generations in (1, 2, 3):
                    with self.subTest(model=model, preset=preset, generations=generations):
                        self.run_case("record", model=model, preset=preset, generations=generations, repeats=3)

    def test_resize_rebuilds_every_solved_level(self):
        for preset in range(3):
            with self.subTest(preset=preset):
                self.run_case("resize", preset=preset, generations=3, repeats=3)

    def test_live_preset_changes_rebuild_the_level_range(self):
        for model in (3, 4):
            with self.subTest(model=model):
                self.run_case("preset", model=model, generations=3, repeats=4)


if __name__ == "__main__":
    unittest.main()
