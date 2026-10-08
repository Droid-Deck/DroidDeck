import contextlib
import hashlib
import io
import json
import os
from pathlib import Path
import runpy
import shutil
import subprocess
import sys
import tempfile
import time
import unittest

from test_esync_packs import make_pack, make_tool

BIN = Path(__file__).resolve().parents[1] / "linuxfs/overlay/usr/local/bin"
RECIPE = runpy.run_path(str(BIN / "droiddeck-recipe"))
SYNC = runpy.run_path(str(BIN / "droiddeck-esync"))
GAME_ENV = runpy.run_path(str(BIN / "droiddeck-game-env"))
COMPAT = runpy.run_path(str(BIN / "steam-compatibility"))
DXVK = "files/lib/wine/dxvk"
VKD3D = "files/lib/wine/vkd3d-proton"
PROTON = ("#!/usr/bin/python3\nimport json, os, sys\n"
          "print(json.dumps([sys.argv[0], sys.argv[1:], {k: os.environ.get(k) for k in "
          "('DXVK_HUD', 'CUSTOM', 'DROIDDECK_RECIPES', 'FEX_TSOENABLED')}]))\n")


def tree_hashes(path):
    """Every entry under `path`, with symlink targets and file contents, for before/after checks."""
    result = {}
    for folder, dirs, names in os.walk(path):
        for name in dirs + names:
            full = os.path.join(folder, name)
            rel = os.path.relpath(full, path)
            if os.path.islink(full):
                result[rel] = "-> " + os.readlink(full)
            elif os.path.isfile(full):
                result[rel] = hashlib.sha256(Path(full).read_bytes()).hexdigest()
            else:
                result[rel] = "dir"
    return result


class RecipeTestCase(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.tmp, True)
        self.home = self.tmp / "home"
        self.root = self.home / ".local/share/droiddeck-recipes"
        self.store = self.root / "store"
        self.store.mkdir(parents=True)
        self.recipes = self.home / ".config/droiddeck/recipes.json"
        self.recipes.parent.mkdir(parents=True)
        RECIPE["store_root"].__globals__["ROOT"] = str(self.root)
        self.depot = self.make_depot(self.tmp / "steamapps/common/Proton Experimental (ARM64)")

    def make_depot(self, depot, line="1789159687 experimental-11.0-20260910b-arm64"):
        for arch in ("aarch64-windows", "i386-windows"):
            for comp, names in ((DXVK, ("d3d9.dll", "d3d11.dll", "dxgi.dll", "openvr_api_dxvk.dll")),
                                (VKD3D, ("d3d12.dll", "d3d12core.dll"))):
                (depot / comp / arch).mkdir(parents=True, exist_ok=True)
                for name in names:
                    (depot / comp / arch / name).write_bytes(b"valve " + name.encode() + arch.encode())
        windows = depot / "files/lib/wine/aarch64-windows"
        unix = depot / "files/lib/wine/aarch64-unix"
        windows.mkdir(parents=True, exist_ok=True)
        unix.mkdir(parents=True, exist_ok=True)
        for name in ("libarm64ecfex.dll", "libwow64fex.dll", "kernel32.dll"):
            (windows / name).write_bytes(b"valve " + name.encode())
        for name in ("libarm64ecfex.so", "libwow64fex.so", "ntdll.so"):
            (unix / name).write_bytes(b"valve " + name.encode())
        (depot / "files/bin-arm64").mkdir(parents=True, exist_ok=True)
        (depot / "files/share/wine").mkdir(parents=True, exist_ok=True)
        (depot / "files/share/wine/wine.inf").write_text("inf")
        (depot / "version").write_text(line + "\n")
        (depot / "proton").write_text(PROTON)
        (depot / "proton").chmod(0o755)
        return depot

    def make_package(self, name, files, complete=True):
        package = self.store / name
        for rel, data in files.items():
            (package / rel).parent.mkdir(parents=True, exist_ok=True)
            (package / rel).write_bytes(data)
        (package / "files").mkdir(parents=True, exist_ok=True)
        if complete:
            (package / ".complete").write_text("1\n")
        return package

    def dxvk_package(self, name="dxvk-2.4-linux", with_i386=True):
        files = {DXVK + "/aarch64-windows/d3d11.dll": b"recipe d3d11", DXVK + "/aarch64-windows/dxgi.dll": b"recipe dxgi"}
        if with_i386:
            files[DXVK + "/i386-windows/d3d11.dll"] = b"recipe d3d11 i386"
        return self.make_package(name, files)

    def write(self, games, version=1):
        self.recipes.write_text(json.dumps({"version": version, "games": games}))

    def lookup(self, app="42", env=None):
        with contextlib.redirect_stderr(io.StringIO()) as said:
            found = RECIPE["lookup"](env if env is not None else {"DROIDDECK_RECIPES": "1"}, app, str(self.recipes))
        return found, said.getvalue()

    def assemble(self, recipe, app="42"):
        command = [str(self.depot / "proton"), "waitforexitandrun", "game.exe"]
        with contextlib.redirect_stderr(io.StringIO()) as said:
            result = RECIPE["assemble"](command, recipe, app, str(self.root))
        return result, said.getvalue()


class LookupTest(RecipeTestCase):
    def test_only_the_auto_tool_and_a_listed_game_get_a_recipe(self):
        package = self.dxvk_package()
        self.write({"42": {"dxvk": str(package)}})
        self.assertIsNone(self.lookup(env={})[0])
        self.assertIsNone(self.lookup(env={"DROIDDECK_RECIPES": "0"})[0])
        self.assertIsNone(self.lookup(app=None)[0])
        self.assertIsNone(self.lookup(app="43")[0])
        found, _ = self.lookup()
        self.assertEqual(found["components"], {"dxvk": os.path.realpath(package)})
        self.recipes.unlink()
        self.assertEqual(self.lookup(), (None, ""))

    def test_a_bad_file_is_no_recipe(self):
        for text in ("{broken", json.dumps({"version": 2, "games": {}}), json.dumps([1]), json.dumps({"version": 1, "games": []})):
            self.recipes.write_text(text)
            found, said = self.lookup()
            self.assertIsNone(found)
            self.assertIn("stock Proton", said)

    def test_packages_must_be_complete_and_in_the_store(self):
        outside = self.tmp / "elsewhere"
        (outside / "files").mkdir(parents=True)
        (outside / ".complete").write_text("1")
        partial = self.make_package("vkd3d-partial", {VKD3D + "/aarch64-windows/d3d12.dll": b"x"}, complete=False)
        link = self.store / "escape"
        os.symlink(outside, link)
        self.write({"42": {"dxvk": str(outside), "vkd3d": str(partial), "fex": str(link)}})
        found, _ = self.lookup()
        self.assertEqual(found["components"], {})
        self.assertEqual(found["skipped"], ["dxvk", "vkd3d", "fex"])
        self.write({"42": {"dxvk": "relative/path", "fex": 7}})
        self.assertEqual(self.lookup()[0]["skipped"], ["dxvk", "fex"])

    def test_environment_is_limited_to_tuning_variables(self):
        allowed = {"DXVK_HUD": "version", "VKD3D_FEATURE_LEVEL": "12_1", "FEX_TSOENABLED": "1", "MESA_SHADER_CACHE_MAX_SIZE": "1G",
                   "PROTON_USE_WINED3D": "1", "mesa_glthread": "true", "WINEDLLOVERRIDES": "xinput1_3=n,b;d3dx9_43=n;libsentry=d;dinput8="}
        denied = {"LD_PRELOAD": "/x.so", "WINEDLLPATH": "/x", "PROTON_LOG_DIR": "/x", "DXVK_CONFIG_FILE": "/x",
                  "VKD3D_SHADER_CACHE_PATH": "/x", "MESA_SHADER_CACHE_DIR": "/x", "FEX_ROOTFS": "/x", "FEX_THUNKHOSTLIBS": "/x",
                  "PATH": "/x", "HOME": "/x", "CUSTOM": "x", "DXVK_HUD2": "a\0b", "BAD-NAME": "x"}
        self.write({"42": {"env": {**allowed, **denied}}})
        found, said = self.lookup()
        self.assertEqual(found["env"], allowed)
        for name in denied:
            self.assertIn(name, said)
        for value in ("../evil.dll=n", "a=n;b=x", "a=native", "a"):
            self.assertFalse(RECIPE["env_allowed"]("WINEDLLOVERRIDES", value), value)
        self.assertFalse(RECIPE["env_allowed"]("DXVK_HUD", "x" * 8193))
        self.assertFalse(RECIPE["env_allowed"]("DXVK_HUD", 1))


class TreeTest(RecipeTestCase):
    def test_the_tree_links_the_depot_and_replaces_only_the_component(self):
        before = tree_hashes(self.depot)
        package = self.dxvk_package()
        self.write({"42": {"dxvk": str(package), "note": "test"}})
        command, said = self.assemble(self.lookup()[0])
        dist = Path(command[0]).parent
        self.assertEqual(command[1:], ["waitforexitandrun", "game.exe"])
        self.assertEqual(dist.parent, self.root / "dist")
        self.assertEqual(os.readlink(dist / "proton"), str(self.depot / "proton"))
        self.assertEqual(os.readlink(dist / "files/share"), str(self.depot / "files/share"))
        self.assertTrue((dist / VKD3D).is_symlink())
        arm = dist / DXVK / "aarch64-windows"
        self.assertFalse(arm.is_symlink())
        real = Path(os.path.realpath(package))
        self.assertEqual({p.name: os.readlink(p) for p in arm.iterdir()}, {
            "d3d11.dll": str(real / DXVK / "aarch64-windows/d3d11.dll"),
            "dxgi.dll": str(real / DXVK / "aarch64-windows/dxgi.dll"),
            "openvr_api_dxvk.dll": str(self.depot / DXVK / "aarch64-windows/openvr_api_dxvk.dll"),
        })
        self.assertNotIn("d3d9.dll", os.listdir(arm))
        self.assertEqual((dist / DXVK / "i386-windows/d3d11.dll").read_bytes(), b"recipe d3d11 i386")
        self.assertEqual((dist / "files/lib/wine/aarch64-unix/ntdll.so").read_bytes(), b"valve ntdll.so")
        self.assertIn("app 42: DXVK dxvk-2.4-linux, VKD3D stock, FEX stock, env 0 (test)", said)
        self.assertEqual(tree_hashes(self.depot), before)
        log = (self.root / "launches.log").read_text().splitlines()
        self.assertEqual(log[-1].split("\t")[1:], ["42", "DXVK dxvk-2.4-linux, VKD3D stock, FEX stock, env 0 (test)"])

    def test_fex_replaces_its_four_files_even_when_the_package_has_no_unix_half(self):
        package = self.make_package("fex-2607", {"files/lib/wine/aarch64-windows/libarm64ecfex.dll": b"fex ec",
                                                 "files/lib/wine/aarch64-windows/libwow64fex.dll": b"fex wow"})
        self.write({"42": {"fex": str(package)}})
        dist = Path(self.assemble(self.lookup()[0])[0][0]).parent
        windows = dist / "files/lib/wine/aarch64-windows"
        unix = dist / "files/lib/wine/aarch64-unix"
        self.assertEqual((windows / "libarm64ecfex.dll").read_bytes(), b"fex ec")
        self.assertEqual(os.readlink(windows / "kernel32.dll"), str(self.depot / "files/lib/wine/aarch64-windows/kernel32.dll"))
        self.assertEqual(sorted(os.listdir(unix)), ["ntdll.so"])
        self.assertTrue((dist / DXVK).is_symlink())

    def test_the_same_inputs_reuse_the_tree_and_changes_make_a_new_one(self):
        package = self.dxvk_package()
        self.write({"42": {"dxvk": str(package)}})
        recipe = self.lookup()[0]
        first = self.assemble(recipe)[0][0]
        marker = Path(first).parent / "marker"
        marker.write_text("kept")
        self.assertEqual(self.assemble(recipe)[0][0], first)
        self.assertTrue(marker.exists())
        (self.depot / "version").write_text("1789999999 experimental-11.0-20261001-arm64\n")
        updated = self.assemble(recipe)[0][0]
        self.assertNotEqual(updated, first)
        os.utime(package / ".complete", (time.time() + 5, time.time() + 5))
        self.assertNotEqual(self.assemble(recipe)[0][0], updated)

    def test_a_recipe_without_packages_or_with_a_failure_is_the_stock_command(self):
        stock = [str(self.depot / "proton"), "waitforexitandrun", "game.exe"]
        self.write({"42": {"env": {"DXVK_HUD": "fps"}}})
        command, said = self.assemble(self.lookup()[0])
        self.assertEqual(command, stock)
        self.assertIn("env 1", said)
        self.assertFalse((self.root / "dist").exists())
        package = self.dxvk_package()
        self.write({"42": {"dxvk": str(package), "vkd3d": str(self.store / "missing")}})
        recipe = self.lookup()[0]
        self.assertIn("VKD3D stock (package missing)", self.assemble(recipe)[1])
        shutil.rmtree(package)
        command, said = self.assemble(recipe)
        self.assertEqual(command, stock)
        self.assertIn("tree not assembled", said)

    def test_old_trees_are_collected(self):
        package = self.dxvk_package()
        self.write({"42": {"dxvk": str(package)}})
        recipe = self.lookup()[0]
        dist = Path(self.assemble(recipe)[0][0]).parent
        folder = dist.parent
        stale = folder / "0123456789abcdef"
        stale.mkdir()
        temp = folder / ".0123.tmp-1"
        temp.mkdir()
        fresh_temp = folder / ".4567.tmp-2"
        fresh_temp.mkdir()
        old = time.time() - RECIPE["DIST_AGE"] - 10
        for path in (stale, dist, temp):
            os.utime(path, (old, old))
        RECIPE["collect"](str(folder), keep=dist.name)
        self.assertFalse(stale.exists())
        self.assertFalse(temp.exists())
        self.assertTrue(fresh_temp.exists())
        self.assertTrue(dist.exists())


class EsyncCompositionTest(RecipeTestCase):
    def test_esync_builds_its_tree_on_top_of_the_recipes(self):
        self.make_depot(self.tmp / "valve/Proton Experimental (ARM64)")
        depot = make_tool(self.tmp / "valve", "Proton Experimental (ARM64)")
        esync = self.home / ".local/share/droiddeck-esync"
        esync.mkdir(parents=True)
        pack = make_pack(esync, depot, "valve-experimental-r1")
        package = self.dxvk_package()
        self.write({"42": {"dxvk": str(package)}})
        with contextlib.redirect_stderr(io.StringIO()):
            command = RECIPE["assemble"]([str(depot / "proton"), "waitforexitandrun", "game.exe"], self.lookup()[0], "42", str(self.root))
            chosen, env = SYNC["select"](command, {"HOME": str(self.home), "STEAM_COMPAT_DATA_PATH": "/compatdata/42",
                                                   "BL_SYNC_FALLBACK": "1", "BL_SYNC_MIN_NOFILE": "1"})
        final = Path(chosen[0]).parent
        self.assertEqual(final.parent, esync / "dist")
        self.assertEqual(env["BL_SYNC_PACK"], pack["id"])
        self.assertEqual((final / DXVK / "aarch64-windows/d3d11.dll").read_bytes(), b"recipe d3d11")
        self.assertEqual((final / SYNC["NTDLL"]).read_bytes(), b"patched ntdll " + pack["id"].encode())
        self.assertEqual((final / "files/lib/wine/aarch64-windows/libarm64ecfex.dll").read_bytes(), b"valve libarm64ecfex.dll")
        self.assertIn("winevulkan.so", os.listdir(final / "files/lib/wine/aarch64-unix"))
        self.assertIn("msidb", os.listdir(final / "files/bin-arm64"))

    def test_esyncs_directories_are_real_in_the_recipe_tree(self):
        # On the device a guest opendir() through a directory symlink fails (proot's fast path), so
        # esync must find every directory it lists already real in this tree.
        self.assertEqual(RECIPE["ESYNC_DIRS"], SYNC["REAL_DIRS"])
        (self.depot / "files/bin-arm64/wineserver").write_bytes(b"server")
        package = self.dxvk_package()
        self.write({"42": {"dxvk": str(package)}})
        dist = Path(self.assemble(self.lookup()[0])[0][0]).parent
        for rel in SYNC["REAL_DIRS"]:
            self.assertTrue((dist / rel).is_dir() and not (dist / rel).is_symlink(), rel)
        self.assertEqual(os.readlink(dist / "files/bin-arm64/wineserver"), str(self.depot / "files/bin-arm64/wineserver"))


class LaunchTest(RecipeTestCase):
    def test_the_auto_launcher_reaches_proton_through_the_recipe_tree(self):
        steam = self.tmp / "Steam"
        depot = self.make_depot(steam / "steamapps/common" / COMPAT["SOURCES"][0])
        tools = steam / "compatibilitytools.d"
        package = self.dxvk_package()
        self.write({"42": {"dxvk": str(package), "env": {"DXVK_HUD": "version", "FEX_TSOENABLED": "0"}}})
        config = self.home / ".config/droiddeck/game-environment.json"
        config.write_text(json.dumps({"version": 1, "shared": {"CUSTOM": "shared", "DXVK_HUD": "fps"},
                                      "games": {"42": {"FEX_TSOENABLED": "1"}}}))
        outputs = {}
        for name in (COMPAT["TOOL"], COMPAT["RECIPE_TOOL"]):
            COMPAT["build_tool"](str(tools / name), str(depot), COMPAT["SOURCES"], name)
            wrapper = tools / name / COMPAT["LAUNCHER"]
            wrapper.write_text(wrapper.read_text().replace("/usr/local/bin/droiddeck-game-env", str(BIN / "droiddeck-game-env")))
            for prefix in ("/compatdata/42", "/compatdata/0"):
                result = subprocess.run([str(wrapper), "waitforexitandrun", "game.exe"], text=True, capture_output=True,
                                        env={"PATH": os.defpath, "HOME": str(self.home), "STEAM_COMPAT_DATA_PATH": prefix,
                                             "STEAM_COMPAT_CLIENT_INSTALL_PATH": str(steam)})
                self.assertEqual(result.returncode, 0, result.stderr)
                outputs[name, prefix] = (json.loads(result.stdout), result.stderr)
        (argv0, args, env), said = outputs[COMPAT["RECIPE_TOOL"], "/compatdata/42"]
        self.assertEqual(Path(argv0).parent.parent, self.root / "dist")
        self.assertEqual(args, ["waitforexitandrun", "game.exe"])
        self.assertEqual(env, {"DXVK_HUD": "version", "CUSTOM": "shared", "DROIDDECK_RECIPES": None, "FEX_TSOENABLED": "1"})
        self.assertIn("droiddeck-recipe: app 42: DXVK dxvk-2.4-linux", said)
        for key in ((COMPAT["TOOL"], "/compatdata/42"), (COMPAT["TOOL"], "/compatdata/0"), (COMPAT["RECIPE_TOOL"], "/compatdata/0")):
            (argv0, _, env), said = outputs[key]
            self.assertEqual(argv0, str(depot / "proton"), key)
            self.assertIsNone(env["DROIDDECK_RECIPES"], key)
            self.assertNotIn("droiddeck-recipe", said, key)
        self.assertEqual(outputs[COMPAT["TOOL"], "/compatdata/42"][0][2]["DXVK_HUD"], "fps")

    def test_recipe_variables_join_the_automatic_fixes(self):
        fixes = {"WINEDLLOVERRIDES": "libsentry=d", "FEX_MULTIBLOCK": "0"}
        merged = GAME_ENV["recipe_fixes"](fixes, {"WINEDLLOVERRIDES": "xinput1_3=n", "FEX_MULTIBLOCK": "1"})
        self.assertEqual(merged, {"WINEDLLOVERRIDES": "libsentry=d;xinput1_3=n", "FEX_MULTIBLOCK": "1"})
        self.assertEqual(fixes["FEX_MULTIBLOCK"], "0")


if __name__ == "__main__":
    unittest.main()
