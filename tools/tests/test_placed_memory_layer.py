"""Exercise the patched layer's dispatch, resources and actual mmap lifetime."""

import ctypes
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
PATCH = ROOT / "tools/hybris/patches/0003-placed-memory-layer.patch"
HELPER = Path(__file__).with_name("placed_memory_layer_test.c")
LAYER = "libVkLayer_DROIDDECK_placed_memory.so"


def vulkan_include():
    if value := os.environ.get("VULKAN_INCLUDE"):
        return Path(value)
    for include in (Path("/usr/include"), Path(sys.base_prefix) / "include"):
        if (include / "vulkan/vulkan.h").is_file():
            return include
    return Path("/usr/include")


class PlacedMemoryLayerTest(unittest.TestCase):
    def checked(self, command, **kwargs):
        completed = subprocess.run(command, capture_output=True, text=True, timeout=60, **kwargs)
        self.assertEqual(completed.returncode, 0, completed.stdout + completed.stderr)
        return completed

    def prepare_source(self, directory):
        checkout = directory / "libhybris"
        if value := os.environ.get("HYBRIS_PLACED_SOURCE"):
            source = Path(value)
            revision = re.search(
                r"^HYBRIS_REVISION=([0-9a-f]{40})$",
                (ROOT / "tools/hybris/source.env").read_text(), re.MULTILINE,
            ).group(1)
            self.assertTrue((source / "hybris/vulkan/Makefile.am").is_file(),
                            "HYBRIS_PLACED_SOURCE must contain the pinned libhybris source")
            self.checked(["git", "clone", "-q", "--no-hardlinks", "--no-checkout",
                          str(source), str(checkout)])
            self.checked(["git", "-C", str(checkout), "checkout", "-q", "--detach", revision])
            patches = [ROOT / "tools/hybris/powervr-hal-version.patch",
                       *sorted((ROOT / "tools/hybris/patches").glob("*.patch"))]
            for patch in patches:
                self.checked(["git", "-C", str(checkout), "apply", "--check", str(patch)])
                self.checked(["git", "-C", str(checkout), "apply", str(patch)])
        else:
            # These source files are new: apply their exact production hunks.
            # The normal suite needs neither network nor a stale C fixture.
            # Component CI also applies the complete stack above.
            checkout.mkdir()
            self.checked(["git", "-C", str(checkout), "apply",
                          "--include=hybris/vulkan/placed/*", str(PATCH)])
        return checkout / "hybris/vulkan/placed"

    def test_native_dispatch_resources_and_mapping_lifetime(self):
        compiler = shutil.which(os.environ.get("CC", "cc"))
        self.assertIsNotNone(compiler, "a C compiler is required")
        include = vulkan_include()
        self.assertTrue((include / "vulkan/vk_layer.h").is_file(),
                        "Vulkan headers including vk_layer.h are required")
        with tempfile.TemporaryDirectory(prefix="droiddeck-placed-layer-") as temporary:
            directory = Path(temporary)
            source = self.prepare_source(directory)
            common = [compiler, "-std=gnu11", "-Wall", "-Wextra", "-Werror", "-pthread",
                      "-I", str(include), "-I", str(source)]
            program = directory / "placed-memory-test"
            self.checked([*common, str(HELPER), "-o", str(program), "-ldl"])
            ran = self.checked([str(program)])
            for contract in ("PASS dispatch:", "PASS map2 only:", "PASS placed features disabled:",
                             "PASS resources:", "PASS mapping:",
                             "PASS concurrency:", "PASS mali resources:",
                             "PASS mali placement:", "PASS total:"):
                self.assertIn(contract, ran.stdout)
            # Symbol lookup is cached for the process lifetime. Separate
            # processes exercise each availability contract without resetting
            # private implementation state between cases.
            fallback = self.checked([str(program), "--fallback-api"])
            self.assertIn("PASS mali placement:", fallback.stdout)
            for option in ("--missing-begin", "--missing-end"):
                missing = self.checked([str(program), option])
                self.assertIn("PASS missing mapping API:", missing.stdout)
                self.assertIn("PASS mapping:", missing.stdout)

            # Verify the actual DSO name and loader negotiation export too.
            # Include-based driver tests alone cannot exercise this linkage.
            manifest = json.loads((source / "VkLayer_DROIDDECK_placed_memory.json").read_text())
            self.assertEqual(manifest["layer"]["library_path"], "./" + LAYER)
            library = directory / LAYER
            self.checked([*common, "-fPIC", "-shared", "-fvisibility=hidden",
                          "-Wl,--no-undefined", "-Wl,-Bsymbolic",
                          str(source / "placed_layer.c"), "-o", str(library), "-ldl"])
            loaded = ctypes.CDLL(str(library))
            self.assertIsNotNone(loaded.vkNegotiateLoaderLayerInterfaceVersion)


if __name__ == "__main__":
    unittest.main()
