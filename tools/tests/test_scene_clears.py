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

# Original d1bd23df with run_case defaults: non-clear trace and modeled content/layout/write-mask hashes.
BASELINE = {
    (0, 0): ("741cbd3ad6812688", "9c35dcbcbf986fcb"),
    (0, 1): ("a50ed826e2d17ffe", "3953b4ca9b2592e0"),
    (0, 2): ("275348f56492c01b", "d5e0acadf785f8de"),
    (0, 3): ("a7cbcb8672519761", "e4081aa0e9bca81a"),
    (0, 4): ("e1caff9151f6c66d", "34b5cdfb9e550963"),
    (0, 5): ("1a2d0fc5ac5690c3", "48d658ad74f7b6e3"),
    (0, 6): ("1346518d9e7fe012", "214f3292c3bcdc77"),
    (0, 7): ("9f5fd34765ba8d2c", "5a6a71625b036e2f"),
    (0, 8): ("fe28dcf086d153f5", "d68847118a4e54fd"),
    (0, 9): ("b8097160d0d54799", "827a69cc9478e973"),
    (0, 10): ("b65d32d200647225", "827a69cc9478e973"),
    (0, 11): ("b65d32d200647225", "827a69cc9478e973"),
    (0, 12): ("b8097160d0d54799", "827a69cc9478e973"),
    (0, 13): ("372d8006dd7d2f7a", "315446a086a23133"),
    (0, 14): ("741cbd3ad6812688", "9c35dcbcbf986fcb"),
    (0, 15): ("4eadfbfbff805afc", "315446a086a23133"),
    (0, 16): ("907d9936d748cd3c", "9c35dcbcbf986fcb"),
    (0, 17): ("741cbd3ad6812688", "9c35dcbcbf986fcb"),
    (0, 18): ("43bfaf7478097095", "07500677d89c744b"),
    (0, 19): ("c8bb5ee1b59dbceb", "9c35dcbcbf986fcb"),
    (0, 20): ("741cbd3ad6812688", "9c35dcbcbf986fcb"),
    (0, 21): ("741cbd3ad6812688", "9c35dcbcbf986fcb"),
    (0, 22): ("741cbd3ad6812688", "9c35dcbcbf986fcb"),
    (0, 23): ("741cbd3ad6812688", "9c35dcbcbf986fcb"),
    (1, 0): ("f543d2f121929bb3", "0c18bb2cd5738723"),
    (1, 1): ("9d84fae448fc68fc", "e67d95f5e9ba1f0d"),
    (1, 2): ("8977a324f3c4c6ea", "ebc7691a9f619ca5"),
    (1, 3): ("8a1000f5095ca768", "8ebda80f0de0ee1d"),
    (1, 4): ("109609155d25f3bc", "f81f976a3a697413"),
    (1, 5): ("dcf5052ddb26a612", "d225772ba2a53f13"),
    (1, 6): ("4af6cf3cb4382535", "4f5761f458d31453"),
    (1, 7): ("028247b1d2d1243f", "a23e47938efa0e43"),
    (1, 8): ("f663faea259bcef9", "cf824627986f4a9f"),
    (1, 9): ("b85b129a100c296b", "46c50f9c971a8e03"),
    (1, 10): ("b85b129a100c296b", "46c50f9c971a8e03"),
    (1, 11): ("b85b129a100c296b", "46c50f9c971a8e03"),
    (1, 12): ("ecbe737bc085aed4", "4d1f9aeafb6052a7"),
    (1, 13): ("6e4e31ed9e630eb5", "4d1f9aeafb6052a7"),
    (1, 14): ("4d0d1c162fbb2233", "4e85e87b867c377b"),
    (1, 15): ("f543d2f121929bb3", "0c18bb2cd5738723"),
    (1, 16): ("291f2a2a56451663", "0c18bb2cd5738723"),
    (1, 17): ("f543d2f121929bb3", "0c18bb2cd5738723"),
    (1, 18): ("4dab4a230abc0a70", "1cbe432a54c52183"),
    (1, 19): ("f543d2f121929bb3", "0c18bb2cd5738723"),
    (1, 20): ("ecbe737bc085aed4", "4d1f9aeafb6052a7"),
    (1, 21): ("72da445d54589bb3", "0c18bb2cd5738723"),
    (1, 22): ("3605cf4f8f129f5c", "0c18bb2cd5738723"),
    (1, 23): ("f543d2f121929bb3", "0c18bb2cd5738723"),
    (2, 0): ("ae39a333d3dc79ee", "0f09de2a9b99db4f"),
    (2, 1): ("33c3c6185ef9ed47", "92ef62d60985568b"),
    (2, 2): ("95ccbb983aa00155", "3890b8f97d41ccbf"),
    (2, 3): ("5dc9af97dcbb7d5f", "b04060060f4035cf"),
    (2, 4): ("099b42ad4070ae2b", "46742fd399940cbf"),
    (2, 5): ("2c2d126e195dd2f9", "542fad5bccba053f"),
    (2, 6): ("f3a8af54fd9e78b0", "fe01a50b184f0eb7"),
    (2, 7): ("f86e8e44819188de", "aa6abec68c34c4e7"),
    (2, 8): ("7c9a2356e807cb23", "d93ce7a1e9c4163b"),
    (2, 9): ("ceb40c736a37fc74", "a0c864e3daccb1bf"),
    (2, 10): ("9906632972d9a7a0", "a0c864e3daccb1bf"),
    (2, 11): ("9906632972d9a7a0", "a0c864e3daccb1bf"),
    (2, 12): ("894af32e456cc49c", "a4d3b125a4a290d3"),
    (2, 13): ("0d2cc6f362a2f72a", "315446a086a23133"),
    (2, 14): ("6c265880f9c84956", "2b6db527b5cacfb7"),
    (2, 15): ("4eadfbfbff805afc", "315446a086a23133"),
    (2, 16): ("94d5fa655b3c3da8", "0f09de2a9b99db4f"),
    (2, 17): ("ae39a333d3dc79ee", "0f09de2a9b99db4f"),
    (2, 18): ("69633b050002470a", "4412c8d19a457cbf"),
    (2, 19): ("31feb4295875027d", "0f09de2a9b99db4f"),
    (2, 20): ("ecbe737bc085aed4", "315446a086a23133"),
    (2, 21): ("62d8aa6738ca79ee", "0f09de2a9b99db4f"),
    (2, 22): ("7bcb0acade8523c3", "0f09de2a9b99db4f"),
    (2, 23): ("ae39a333d3dc79ee", "0f09de2a9b99db4f"),
}


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

    def test_commands_and_model_contents_match_original_main(self):
        for (path, case), (trace, pixels) in BASELINE.items():
            with self.subTest(path=path, case=case):
                result = self.run_case(path, case)
                self.assertEqual(result["trace"], int(trace, 16))
                self.assertEqual(result["pixels"], int(pixels, 16))

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
