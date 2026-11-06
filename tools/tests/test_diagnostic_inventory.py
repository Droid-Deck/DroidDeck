import io
import json
import os
from pathlib import Path
import runpy
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "tools/linuxfs/overlay/usr/local/bin/droiddeck-diagnostic-inventory"
MODULE = runpy.run_path(str(SCRIPT), run_name="droiddeck_diagnostic_inventory")


def write_process(root, pid, ppid, start, comm, cmdline, maps=None):
    process = root / str(pid)
    process.mkdir(parents=True)
    children = process / "task" / str(pid) / "children"
    children.parent.mkdir(parents=True)
    children.write_text("")
    fields = ["S", str(ppid)] + ["0"] * 17 + [str(start)]
    (process / "stat").write_text(f"{pid} ({comm}) " + " ".join(fields) + "\n")
    (process / "comm").write_text(comm + "\n")
    (process / "cmdline").write_bytes(cmdline.replace(" ", "\0").encode() + b"\0")
    if maps is not None:
        (process / "maps").write_text("".join(
            f"1000-2000 r-xp 00000000 00:00 0 {path}\n" for path in maps
        ))
    parent_children = root / str(ppid) / "task" / str(ppid) / "children"
    if parent_children.is_file():
        existing = parent_children.read_text().split()
        if str(pid) not in existing:
            parent_children.write_text(" ".join(existing + [str(pid)]) + "\n")
    return process


def rows(text):
    prefix = MODULE["PREFIX"]
    return [json.loads(line.removeprefix(prefix)) for line in text.splitlines() if line.startswith(prefix)]


class DiagnosticInventoryTest(unittest.TestCase):
    def test_default_off_produces_no_output(self):
        with tempfile.TemporaryDirectory() as directory:
            output = io.StringIO()
            result = MODULE["main"](
                ["--once", "--root-pid", "100", "--proc-root", directory], {}, output,
            )
            self.assertEqual(result, 0)
            self.assertEqual(output.getvalue(), "")

    def test_once_scopes_processes_and_reports_only_safe_graphics_identity(self):
        with tempfile.TemporaryDirectory() as directory:
            proc = Path(directory)
            write_process(proc, 100, 1, 10, "droiddeck-session", "/usr/local/bin/droiddeck-session steam", [
                "/usr/lib/libvulkan.so.1",
            ])
            write_process(proc, 101, 100, 11, "steam", "/secret/account/steam -login private-token", [
                "/data/user/0/com.droiddeck.launcher/files/components/system-vulkan/abc/libVkLayer_hybris_compat.so",
                "/data/user/0/com.droiddeck.launcher/files/components/system-vulkan/abc/libvulkan-private-account.so",
                "/data/user/0/com.droiddeck.launcher/files/components/system-vulkan/abc/libdrm_accountSecret.so",
                "/data/user/0/com.droiddeck.launcher/files/components/system-vulkan/abc/libxcb-sessionSecret.so",
                "/data/user/0/com.droiddeck.launcher/files/components/system-vulkan/abc/libvkd3d-userSecret.so",
                "/data/user/0/com.droiddeck.launcher/files/components/system-vulkan/abc/libdrm_amdgpu.so.1.0.0",
                "/home/private-user/not-graphics-secret.so",
                "/home/private-user/libvulkan-private-user.so",
            ])
            write_process(proc, 102, 101, 12, "steamwebhelper", "steamwebhelper --user-data-dir=/secret/profile", [
                "/vendor/lib64/hw/vulkan.samsung.so",
                "/tmp/libvulkan-secret with spaces.so",
            ])
            write_process(proc, 103, 101, 13, "private-game-title", "private-game-title --token=child-secret", [])
            write_process(proc, 200, 1, 20, "unrelated", "unrelated --password=hunter2", [
                "/vendor/lib64/hw/vulkan.unrelated.so",
            ])
            output = io.StringIO()
            MODULE["main"](
                ["--once", "--root-pid", "100", "--proc-root", directory],
                {"DROIDDECK_ENHANCED_DIAGNOSTICS": "1"}, output,
            )
            payloads = rows(output.getvalue())
            self.assertEqual(payloads[0]["event"], "provider")
            snapshot = payloads[1]
            self.assertEqual(snapshot["alive"], 4)
            self.assertEqual(snapshot["roles"], {
                "game-or-helper": 1, "session": 1, "steam-client": 1, "steam-webhelper": 1,
            })
            self.assertEqual(snapshot["providerStatus"], "available")
            self.assertEqual(snapshot["scopeMethod"], "children")
            self.assertFalse(snapshot["scopeIncomplete"])
            libraries = {(item["name"], item["origin"]) for item in snapshot["libraries"]}
            self.assertIn(("libvulkan.so.1", "guest-runtime"), libraries)
            self.assertIn(("libVkLayer_hybris_compat.so", "droiddeck-component"), libraries)
            self.assertIn(("libdrm_amdgpu.so.1.0.0", "droiddeck-component"), libraries)
            self.assertIn(("vulkan.samsung.so", "android-vendor"), libraries)
            self.assertTrue(any(name.startswith("sha256:") and origin == "other" for name, origin in libraries))
            self.assertGreaterEqual(sum(name.startswith("sha256:") and origin == "droiddeck-component"
                                        for name, origin in libraries), 4)
            text = output.getvalue()
            for secret in ("private-token", "private-user", "child-secret", "private-game-title", "hunter2",
                           "accountSecret", "sessionSecret", "userSecret", "unrelated", "/vendor/", "/data/user/"):
                self.assertNotIn(secret, text)

    def test_missing_maps_and_proc_are_explicit(self):
        with tempfile.TemporaryDirectory() as directory:
            proc = Path(directory)
            write_process(proc, 100, 1, 10, "droiddeck-session", "droiddeck-session")
            collector = MODULE["Collector"](proc, 100)
            snapshot, _ = collector.scan()
            self.assertEqual(snapshot["maps"]["unavailable"], 1)
            self.assertEqual(snapshot["providerStatus"], "available")
            missing = MODULE["Collector"](proc / "missing", 100)
            snapshot, _ = missing.scan()
            self.assertEqual(snapshot["providerStatus"], "proc-unavailable")

    def test_children_fallback_is_explicitly_incomplete(self):
        with tempfile.TemporaryDirectory() as directory:
            proc = Path(directory)
            process = write_process(proc, 100, 1, 10, "droiddeck-session", "droiddeck-session", [])
            write_process(proc, 101, 100, 11, "steam", "steam", [])
            (process / "task" / "100" / "children").unlink()
            collector = MODULE["Collector"](proc, 100)
            snapshot, _ = collector.scan()
            self.assertEqual(snapshot["alive"], 2)
            self.assertEqual(snapshot["scopeMethod"], "children+scan-fallback")
            self.assertTrue(snapshot["scopeIncomplete"])

    def test_pid_reuse_is_an_exit_and_a_new_process(self):
        with tempfile.TemporaryDirectory() as directory:
            proc = Path(directory)
            write_process(proc, 99, 1, 9, "droiddeck-session", "droiddeck-session", [])
            process = write_process(proc, 100, 99, 10, "steam", "steam", [])
            collector = MODULE["Collector"](proc, 99)
            first, _ = collector.scan()
            first_token = next(row["token"] for row in first["started"] if row["role"] == "steam-client")
            fields = ["S", "99"] + ["0"] * 17 + ["20"]
            (process / "stat").write_text("100 (steam) " + " ".join(fields) + "\n")
            second, changed = collector.scan()
            self.assertTrue(changed)
            self.assertEqual(next(row["token"] for row in second["exited"] if row["role"] == "steam-client"), first_token)
            self.assertNotEqual(
                next(row["token"] for row in second["started"] if row["role"] == "steam-client"), first_token,
            )

    def test_exec_role_change_is_reported_without_pid_reuse(self):
        with tempfile.TemporaryDirectory() as directory:
            proc = Path(directory)
            process = write_process(proc, 100, 1, 10, "droiddeck-session", "droiddeck-session", [])
            collector = MODULE["Collector"](proc, 100)
            collector.scan()
            (process / "comm").write_text("gamescope\n")
            (process / "cmdline").write_bytes(b"gamescope\0--backend\0wayland\0")
            snapshot, changed = collector.scan()
            self.assertTrue(changed)
            self.assertEqual(snapshot["roles"], {"gamescope": 1})

    def test_process_scope_is_bounded(self):
        with tempfile.TemporaryDirectory() as directory:
            proc = Path(directory)
            write_process(proc, 100, 1, 10, "droiddeck-session", "droiddeck-session", [])
            for pid in range(101, 370):
                write_process(proc, pid, 100, pid, "helper", "helper", [])
            collector = MODULE["Collector"](proc, 100)
            snapshot, _ = collector.scan()
            self.assertLessEqual(snapshot["alive"], MODULE["MAX_PROCESSES"])
            self.assertTrue(snapshot["scopeCapped"])

    def test_reused_root_pid_is_not_adopted(self):
        with tempfile.TemporaryDirectory() as directory:
            proc = Path(directory)
            process = write_process(proc, 100, 1, 10, "droiddeck-session", "droiddeck-session", [])
            collector = MODULE["Collector"](proc, 100)
            collector.scan()
            fields = ["S", "1"] + ["0"] * 17 + ["20"]
            (process / "stat").write_text("100 (unrelated-app) " + " ".join(fields) + "\n")
            snapshot, _ = collector.scan()
            self.assertEqual(snapshot["providerStatus"], "root-reused")
            self.assertEqual(snapshot["alive"], 0)
            self.assertNotIn("unrelated-app", json.dumps(snapshot))

    def test_reparented_child_is_rejected_after_process_reads(self):
        with tempfile.TemporaryDirectory() as directory:
            proc = Path(directory)
            write_process(proc, 100, 1, 10, "droiddeck-session", "droiddeck-session", [])
            child = write_process(proc, 101, 100, 11, "steam", "steam", [])
            globals_ = MODULE["Collector"].scan.__globals__
            original = globals_["process_stat"]
            calls = 0

            def racing(path):
                nonlocal calls
                if path == child / "stat":
                    calls += 1
                    if calls == 3:
                        fields = ["S", "1"] + ["0"] * 17 + ["11"]
                        path.write_text("101 (steam) " + " ".join(fields) + "\n")
                return original(path)

            globals_["process_stat"] = racing
            try:
                collector = MODULE["Collector"](proc, 100)
                snapshot, _ = collector.scan()
            finally:
                globals_["process_stat"] = original
            self.assertEqual(snapshot["roles"], {"session": 1})
            self.assertEqual(snapshot["alive"], 1)

    def test_watch_is_bounded_and_emits_heartbeats_without_process_changes(self):
        globals_ = MODULE["main"].__globals__
        original = globals_["HEARTBEAT_MS"]
        globals_["HEARTBEAT_MS"] = 40
        try:
            with tempfile.TemporaryDirectory() as directory:
                proc = Path(directory)
                write_process(proc, 100, 1, 10, "droiddeck-session", "droiddeck-session", [])
                output = io.StringIO()
                MODULE["main"](
                    ["--watch", "--root-pid", "100", "--proc-root", directory,
                     "--interval-ms", "250", "--duration-ms", "520", "--max-records", "4"],
                    {"DROIDDECK_ENHANCED_DIAGNOSTICS": "1"}, output,
                )
                payloads = rows(output.getvalue())
                self.assertEqual(payloads[0]["event"], "provider")
                self.assertEqual(payloads[1]["event"], "snapshot")
                self.assertEqual(payloads[2]["event"], "heartbeat")
                self.assertNotIn("libraries", payloads[2])
                self.assertTrue(payloads[2]["librariesUnchanged"])
                self.assertLessEqual(len(payloads), 4)
        finally:
            globals_["HEARTBEAT_MS"] = original


if __name__ == "__main__":
    unittest.main()
