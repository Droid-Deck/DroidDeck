import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import unittest


REPO = Path(__file__).resolve().parents[2]
SOURCE = REPO / "app/src/main/cpp/waylandcomp/src/vk_present.c"
NATIVE = SOURCE.parent


def function(source, signature):
    start = source.index(signature)
    while source.index(";", start) < source.index("{", start):
        start = source.index(signature, start + len(signature))
    return source[start:source.index("\n}\n", start) + 3]


def build_harness(source, folder, compiler, headers, sanitize=False):
    android = folder / "android"
    android.mkdir(exist_ok=True)
    (android / "native_window.h").write_text("typedef struct ANativeWindow ANativeWindow;\n")
    declarations = []
    for name in ("struct vkp_image {", "struct vkp_img_slot {"):
        start = source.index(name)
        declarations.append(source[start:source.index("};", start) + 2])
    start = source.index("static struct {\n    int scene_w, scene_h")
    declarations.append(source[start:source.index("} g_map;", start) + len("} g_map;")])
    for name in ("SCENE_FMT", "HDR_FMT", "FG_HDR_FMT"):
        declarations.append(re.search(r"^#define " + name + r" .+$", source, re.MULTILINE).group())
    (folder / "present_types.inc").write_text("\n".join(declarations))
    names = ["static int map_draw(", "static int draw_to_blit(", "static int draw_to_scene_blit("]
    helpers = "static int find_opaque_cover(" in source
    if helpers:
        names += ["static int find_opaque_cover(", "static int scene_fully_replaced("]
    names += ["static void record_draw(", "static int compose_hdr(", "static int pass_begin_impl(",
              "int vkp_pass_begin(", "int vkp_pass_begin_hdr("]
    (folder / "present_functions.inc").write_text(
        "#define HAS_COVER_HELPERS %d\n" % helpers + "\n".join(function(source, name) for name in names))
    include = folder / "include"
    if not include.exists():
        shutil.copytree(headers, include / "vulkan", ignore=shutil.ignore_patterns("*.hpp"))
        if (headers.parent / "vk_video").is_dir():
            shutil.copytree(headers.parent / "vk_video", include / "vk_video")
    binary = folder / ("scene-clears-sanitized" if sanitize else "scene-clears")
    command = [compiler, "-std=c11", "-Wall", "-Wextra", "-Werror", "-Wno-unused-parameter", "-Wno-unused-function",
               "-Wno-unused-variable", "-I" + str(folder), "-I" + str(include), "-I" + str(NATIVE)]
    command += ["-O1", "-g", "-fsanitize=address,undefined"] if sanitize else ["-O2"]
    command += [str(REPO / "tools/tests/scene_clear_harness.c"), "-lm", "-o", str(binary)]
    result = subprocess.run(command, capture_output=True, text=True, timeout=120)
    if result.returncode:
        raise RuntimeError(result.stdout + result.stderr)
    return binary


def vulkan_headers():
    candidates = [Path("/usr/include/vulkan"), Path("/opt/homebrew/include/vulkan")]
    if os.environ.get("VK_INCLUDE"):
        candidates.insert(0, Path(os.environ["VK_INCLUDE"]) / "vulkan")
    for name in ("ANDROID_NDK_HOME", "ANDROID_NDK_ROOT", "NDK_HOME"):
        if os.environ.get(name):
            candidates.extend(Path(os.environ[name]).glob("toolchains/llvm/prebuilt/*/sysroot/usr/include/vulkan"))
    for name in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
        if os.environ.get(name):
            candidates.extend(Path(os.environ[name]).glob("ndk/*/toolchains/llvm/prebuilt/*/sysroot/usr/include/vulkan"))
    return next((path for path in candidates if (path / "vulkan.h").is_file()), None)


class SceneClearTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        compiler = shutil.which(os.environ.get("CC", "cc"))
        headers = vulkan_headers()
        if compiler is None or headers is None:
            raise unittest.SkipTest("A host C compiler and Vulkan headers are required")
        cls.temp = tempfile.TemporaryDirectory(prefix="droiddeck-scene-clear-")
        cls.addClassCleanup(cls.temp.cleanup)
        cls.binary = build_harness(SOURCE.read_text(), Path(cls.temp.name), compiler, headers)

    def run_case(self, path, case, w=8, h=6, fmt=0, dmabuf=0, effects=1, frames=1):
        result = subprocess.run([str(self.binary), str(path), str(case), str(w), str(h),
                                 str(fmt), str(dmabuf), str(effects), str(frames)],
                                capture_output=True, text=True, timeout=10)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        return {key: int(value, 16) if key in ("trace", "pixels") else int(value)
                for key, value in (part.split("=") for part in result.stdout.split())}

    def test_existing_main_coverage_search_is_preserved(self):
        result = subprocess.run([str(self.binary), "cover"], capture_output=True, text=True, timeout=10)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual(result.stdout.strip(), "PASS")

    def test_single_full_blits_do_not_clear_intermediates(self):
        for path in range(3):
            for case in (0, 6, 7, 19, 21, 22, 23):
                with self.subTest(path=path, case=case):
                    result = self.run_case(path, case)
                    self.assertEqual(result["clears"], 0)
                    self.assertEqual(result["blits"], 1)

    def test_partial_blended_multiple_and_invalid_draws_keep_the_clear(self):
        for path in range(3):
            for case in (1, 2, 3, 4, 5, 8, 9, 10, 11, 16, 17, 18):
                with self.subTest(path=path, case=case):
                    self.assertEqual(self.run_case(path, case)["clears"], 1)

    def test_source_formats_and_memory_types_preserve_the_fast_path(self):
        for path in range(3):
            for fmt in range(4):
                for dmabuf in range(2):
                    with self.subTest(path=path, fmt=fmt, dmabuf=dmabuf):
                        result = self.run_case(path, 0, fmt=fmt, dmabuf=dmabuf)
                        self.assertEqual(result["clears"], 0)
                        self.assertEqual(result["blits"], 1)

    def test_empty_scene_keeps_its_existing_behavior(self):
        self.assertEqual(self.run_case(0, 12)["clears"], 1)
        self.assertEqual(self.run_case(1, 12)["clears"], 0)
        self.assertEqual(self.run_case(2, 12)["clears"], 0)

    def test_allocation_mapping_and_encode_failures_keep_their_returns(self):
        for path in range(3):
            with self.subTest(path=path):
                self.assertEqual(self.run_case(path, 13)["result"], -1)
                self.assertEqual(self.run_case(path, 15)["result"], 0 if path == 1 else -1)
                self.assertEqual(self.run_case(path, 14)["result"], 0 if path == 0 else -1)

    def test_null_hdr_input_keeps_the_existing_failure(self):
        for path in (1, 2):
            with self.subTest(path=path):
                result = self.run_case(path, 20)
                self.assertEqual(result["result"], -1)
                self.assertEqual(result["clears"], 0)

    def test_repeated_frames_and_resizes_do_not_require_cached_coverage(self):
        for path in range(3):
            for case in (0, 1, 2, 6, 8, 23):
                with self.subTest(path=path, case=case):
                    result = self.run_case(path, case, frames=3)
                    self.assertEqual(result["clears"], 3 if case in (1, 2, 8) else 0)


if __name__ == "__main__":
    unittest.main()
