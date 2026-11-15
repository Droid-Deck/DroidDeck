import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[2]
PATCH = ROOT / "tools/gamescope/patches/0900-enhanced-frame-diagnostics.patch"


def added_file(patch, name):
    lines = patch.read_text().splitlines()
    marker = f"diff --git a/{name} b/{name}"
    start = lines.index(marker)
    output = []
    in_hunk = False
    for line in lines[start + 1:]:
        if line.startswith("diff --git "):
            break
        if line.startswith("@@"):
            in_hunk = True
        elif in_hunk and line.startswith("+") and not line.startswith("+++"):
            output.append(line[1:])
    return "\n".join(output) + "\n"


class GamescopeDiagnosticsTest(unittest.TestCase):
    def test_patch_covers_each_frame_boundary_without_payload_logging(self):
        text = PATCH.read_text()
        for path in ("src/wlserver.cpp", "src/steamcompmgr.cpp", "src/Backends/WaylandBackend.cpp"):
            self.assertIn(f"diff --git a/{path} b/{path}", text)
        self.assertIn("diff --git a/src/main.cpp b/src/main.cpp", text)
        self.assertIn("droiddeck::frame_trace::Start();", text)
        self.assertIn("droiddeck::frame_trace::Tick();", text)
        self.assertIn("pTraceFocus->focusWindow == w", text)
        self.assertNotIn("GetFocusForWindow( w ) != nullptr", text)
        for event in (
            "X11Damage", "X11DamageCurrent", "X11DamageOther", "SurfaceCommit",
            "SurfaceCommitBuffer", "SurfaceCommitNoBuffer", "CommitDequeued",
            "CommitDequeuedCurrent", "CommitDequeuedOther", "ImportSucceeded",
            "ImportSucceededCurrent", "ImportSucceededOther", "ImportFailed",
            "ImportFailedCurrent", "ImportFailedOther", "FenceReady", "FenceWaited",
            "CommitDone", "CommitDoneCurrent", "CommitDoneOther", "BaseLatched",
            "BaseLatchedCurrent", "BaseLatchedOther",
            "OutputPresent", "OutputAttach", "OutputDetach",
            "OutputCommit", "OutputRelease",
        ):
            self.assertIn(f"Event::{event}", text)
        header = added_file(PATCH, "src/droiddeck_frame_trace.hpp")
        self.assertIn('std::getenv( "DROIDDECK_ENHANCED_DIAGNOSTICS" )', header)
        self.assertIn("DROIDDECK_FRAME_TRACE_FAST_INTERVAL_MS 2000", header)
        self.assertIn("DROIDDECK_FRAME_TRACE_SLOW_INTERVAL_MS 15000", header)
        self.assertIn("DROIDDECK_FRAME_TRACE_DURATION_MS 86400000", header)
        self.assertNotRegex(header, re.compile(r"title|pixel|address|argv", re.I))

    def test_collector_is_default_off_bounded_and_thread_safe(self):
        compiler = shutil.which(os.environ.get("CXX", "c++"))
        if not compiler:
            self.skipTest("a C++ compiler is required")
        with tempfile.TemporaryDirectory(prefix="gamescope-frame-trace-") as directory:
            work = Path(directory)
            (work / "droiddeck_frame_trace.hpp").write_text(
                added_file(PATCH, "src/droiddeck_frame_trace.hpp"))
            (work / "trace.cpp").write_text(r'''
#include "droiddeck_frame_trace.hpp"
#include <chrono>
#include <thread>
#include <vector>
int main() {
    droiddeck::frame_trace::Start();
    std::vector<std::thread> threads;
    for (int i = 0; i < 4; ++i)
        threads.emplace_back([] { for (int n = 0; n < 1000; ++n)
            droiddeck::frame_trace::RecordWindow(
                droiddeck::frame_trace::Event::X11Damage,
                droiddeck::frame_trace::Event::X11DamageCurrent,
                droiddeck::frame_trace::Event::X11DamageOther,
                false); });
    for (auto &thread : threads) thread.join();
    std::this_thread::sleep_for(std::chrono::milliseconds(60));
    droiddeck::frame_trace::Tick();
    std::this_thread::sleep_for(std::chrono::milliseconds(70));
    droiddeck::frame_trace::Tick();
    droiddeck::frame_trace::Record(droiddeck::frame_trace::Event::SurfaceCommit);
    droiddeck::frame_trace::RecordWindow(
        droiddeck::frame_trace::Event::ImportSucceeded,
        droiddeck::frame_trace::Event::ImportSucceededCurrent,
        droiddeck::frame_trace::Event::ImportSucceededOther,
        true);
    std::this_thread::sleep_for(std::chrono::milliseconds(70));
    droiddeck::frame_trace::Tick();
    std::this_thread::sleep_for(std::chrono::milliseconds(120));
    droiddeck::frame_trace::Tick();
    droiddeck::frame_trace::Record(droiddeck::frame_trace::Event::OutputPresent);
    droiddeck::frame_trace::Tick();
}
''')
            program = work / "trace"
            built = subprocess.run([
                compiler, "-std=c++20", "-Wall", "-Wextra", "-Werror", "-pthread",
                "-DDROIDDECK_FRAME_TRACE_FAST_INTERVAL_MS=50",
                "-DDROIDDECK_FRAME_TRACE_SLOW_INTERVAL_MS=60",
                "-DDROIDDECK_FRAME_TRACE_FAST_DURATION_MS=100",
                "-DDROIDDECK_FRAME_TRACE_DURATION_MS=300",
                str(work / "trace.cpp"), "-o", str(program),
            ], capture_output=True, text=True)
            self.assertEqual(0, built.returncode, built.stdout + built.stderr)
            quiet = subprocess.run([str(program)], capture_output=True, text=True, timeout=10)
            self.assertEqual(0, quiet.returncode, quiet.stdout + quiet.stderr)
            self.assertEqual("", quiet.stderr)
            env = os.environ.copy()
            env["DROIDDECK_ENHANCED_DIAGNOSTICS"] = "1"
            traced = subprocess.run([str(program)], env=env, capture_output=True, text=True, timeout=10)
            self.assertEqual(0, traced.returncode, traced.stdout + traced.stderr)
            self.assertEqual(1, traced.stderr.count("schema=1"))
            summaries = [line for line in traced.stderr.splitlines() if "t_ms=" in line]
            self.assertIn("t_ms=0 x11_damage=0", summaries[0])
            self.assertTrue(any("x11_damage=4000" in line and "x11_damage_current=0" in line and
                                "x11_damage_other=4000" in line and "surface_commit=0" in line
                                for line in summaries[1:]))
            self.assertTrue(any("surface_commit=1" in line for line in summaries[1:]))
            self.assertTrue(any("import_ok=1" in line and "import_ok_current=1" in line and
                                "import_ok_other=0" in line for line in summaries[1:]))
            self.assertIn("t_ms=300", summaries[-1])
            self.assertIn("output_present=0", summaries[-1])
            self.assertLessEqual(len(summaries), 5)


if __name__ == "__main__":
    unittest.main()
