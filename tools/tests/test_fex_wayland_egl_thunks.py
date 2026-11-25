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
    out, base, stack = set(), False, []
    for line in block.splitlines():
        if line.startswith("@@"):
            base, stack = "BITNESS EQUAL 64" in line, []
            continue
        if line[:1] == "-":
            continue
        text = line[1:].strip()
        if text.startswith("if ("):
            stack.append("gate" if "BITNESS EQUAL 64" in text else "other")
        elif text.startswith("else"):
            if stack:
                stack[-1] = {"gate": "inverted", "inverted": "gate"}.get(stack[-1], stack[-1])
            else:
                base = False
        elif text == "endif()":
            if stack:
                stack.pop()
            else:
                base = False
        found = re.match(r"add_(?:guest|host)_lib\(([\w-]+)", text)
        if found and line[:1] == "+":
            out.add((found.group(1), "inverted" not in stack and (base or "gate" in stack)))
    return out


def database():
    entries, name = {}, None
    for line in blocks()["Data/ThunksDB.json"].splitlines():
        if line[:1] == "-":
            continue
        found = re.match(r'[ +]\s*"(\w+)": \{', line)
        if found:
            name = found.group(1)
        found = re.search(r'"Library" : "([^"]+)"', line)
        if found and name:
            entries[name] = found.group(1)
    return entries


class FexWaylandEglThunksTest(unittest.TestCase):
    def test_only_thunk_sources_and_the_thunk_database_change(self):
        for name in files():
            with self.subTest(name=name):
                self.assertTrue(name == "Data/ThunksDB.json" or name.startswith("ThunkLibs/"))

    def test_each_enabled_thunk_maps_to_the_guest_library_the_build_ships(self):
        launcher = runpy.run_path(str(LAUNCHER), run_name="droiddeck_fex")["WAYLAND_THUNKS"]
        self.assertEqual(set(THUNKS), set(launcher))
        built = {name for name, _ in built_libs(blocks()["ThunkLibs/GuestLibs/CMakeLists.txt"])}
        entries = database()
        for name, lib in THUNKS.items():
            with self.subTest(name=name):
                self.assertIn(lib, built)
                self.assertEqual(f"lib{lib}-guest.so", launcher[name])
                if name in entries:
                    self.assertEqual(launcher[name], entries[name])
        self.assertEqual({"EGL", "WaylandEGL"}, set(entries) & set(THUNKS))

    def test_gles_is_overlaid_only_by_the_64_bit_egl_thunk(self):
        overlays, name = {}, None
        for line in blocks()["Data/ThunksDB.json"].splitlines():
            found = re.match(r'[ +]\s*"(\w+)": \{', line)
            if found:
                name = found.group(1)
            if "libGLESv2" in line:
                overlays.setdefault(line[:1], set()).add(name)
        self.assertEqual({"EGL"}, overlays.get("+"))
        self.assertNotIn("-", overlays)

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

    def test_the_inverse_branch_of_the_bitness_gate_is_not_64_bit_only(self):
        block = "@@ -1 +1 @@\n+if (GUEST_BITNESS EQUAL 64)\n+else()\n+  add_host_lib(EGL ${GUEST_BITNESS})\n+endif()\n"
        self.assertEqual({("EGL", False)}, built_libs(block))

    def test_no_comments_are_added(self):
        for name, lines in files().items():
            for line in lines:
                with self.subTest(name=name, line=line):
                    self.assertNotRegex(line, r"(^|\s)(//|/\*)|\$info\$")


if __name__ == "__main__":
    unittest.main()
