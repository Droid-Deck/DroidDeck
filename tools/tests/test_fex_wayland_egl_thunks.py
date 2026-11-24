import re
import runpy
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
PATCH = REPO / "tools/fex/patches/0019-wayland-and-egl-thunks-for-64-bit-guests.patch"
LAUNCHER = REPO / "tools/linuxfs/overlay/usr/local/bin/droiddeck-fex"
THUNKS = {"WaylandClient": "wayland-client", "EGL": "EGL", "WaylandEGL": "wayland-egl"}


def blocks():
    return {re.match(r"a/(\S+) ", block).group(1): block
            for block in re.split(r"^diff --git ", PATCH.read_text(), flags=re.M)[1:]}


def files():
    return {name: [line[1:] for line in block.splitlines() if line.startswith("+") and not line.startswith("+++")]
            for name, block in blocks().items()}


def built_libs(block):
    out, gated, depth = set(), False, 0
    for line in block.splitlines():
        if line.startswith("@@"):
            gated, depth = "BITNESS EQUAL 64" in line, 0
            continue
        if line[:1] == "-":
            continue
        text = line[1:].strip()
        if text.startswith("if (") and "BITNESS EQUAL 64" in text:
            depth += 1
        elif text == "endif()" and depth:
            depth -= 1
        elif text == "endif()":
            gated = False
        found = re.match(r"add_(?:guest|host)_lib\(([\w-]+)", text)
        if found and line[:1] == "+":
            out.add((found.group(1), gated or depth > 0))
    return out


class FexWaylandEglThunksTest(unittest.TestCase):
    def test_only_thunk_sources_and_the_thunk_database_change(self):
        for name in files():
            with self.subTest(name=name):
                self.assertTrue(name == "Data/ThunksDB.json" or name.startswith("ThunkLibs/"))

    def test_the_database_names_every_thunk_the_launcher_enables(self):
        added = "\n".join(files()["Data/ThunksDB.json"])
        launcher = runpy.run_path(str(LAUNCHER), run_name="droiddeck_fex")
        for name, guest in launcher["WAYLAND_THUNKS"].items():
            with self.subTest(name=name):
                self.assertIn(name, THUNKS)
                self.assertIn(f"lib{THUNKS[name]}-guest.so", guest)
        for name in ("EGL", "WaylandEGL"):
            self.assertIn(f'"{name}"', added)

    def test_guest_and_host_libraries_are_built_for_64_bit_guests_only(self):
        changed = blocks()
        for side in ("GuestLibs", "HostLibs"):
            built = built_libs(changed[f"ThunkLibs/{side}/CMakeLists.txt"])
            for lib in THUNKS.values():
                with self.subTest(side=side, lib=lib):
                    self.assertEqual({(lib, True)}, {entry for entry in built if entry[0] == lib})

    def test_listener_trampolines_stay_inside_the_packed_argument_limit(self):
        guest = "\n".join(files()["ThunkLibs/libwayland-client/Guest.cpp"])
        limit = int(re.search(r"#define WAYLAND_UNIFORM_MAX_ARGS (\d+)", guest).group(1))
        self.assertLessEqual(limit + 2, 19)

    def test_the_guest_provides_the_inline_list_and_array_helpers(self):
        guest = "\n".join(files()["ThunkLibs/libwayland-client/Guest.cpp"])
        for name in ("wl_list_init", "wl_list_insert", "wl_list_remove", "wl_list_length", "wl_list_empty",
                     "wl_list_insert_list", "wl_array_init", "wl_array_release", "wl_array_add", "wl_array_copy"):
            with self.subTest(name=name):
                self.assertRegex(guest, rf"\b{name}\(")

    def test_no_comments_are_added(self):
        for name, lines in files().items():
            for line in lines:
                with self.subTest(name=name, line=line):
                    self.assertNotRegex(line, r"(^|\s)(//|/\*)|\$info\$")


if __name__ == "__main__":
    unittest.main()
