import re
import unittest
from pathlib import Path

BRIDGE = Path(__file__).resolve().parents[2] / "app/src/main/cpp/waylandcomp/src/framegen_bridge.c"


def function_body(source, name):
    match = re.search(r"\b" + re.escape(name) + r"\([^)]*\)\s*\{", source)
    if match is None:
        raise AssertionError(f"{name} not found")
    depth, start = 1, match.end()
    for index in range(start, len(source)):
        if source[index] == "{":
            depth += 1
        elif source[index] == "}":
            depth -= 1
            if depth == 0:
                return source[start:index]
    raise AssertionError(f"{name} has no closing brace")


class FrameGenStatsTest(unittest.TestCase):
    def setUp(self):
        self.source = BRIDGE.read_text(encoding="utf-8")

    def test_stats_never_enter_the_engine_or_wait_on_a_lock(self):
        body = function_body(self.source, "vkp_framegen_stats")
        self.assertNotRegex(body, r"\bfge_\w+\s*\(")
        self.assertNotIn("pthread_mutex_lock", body)
        self.assertIn("atomic_load(&g_thermal)", body)

    def test_telemetry_is_sampled_on_the_render_thread_after_planning(self):
        body = function_body(self.source, "vkp_framegen_run")
        plan = body.find("fge_plan(")
        telemetry = body.find("fge_telemetry(")
        self.assertGreaterEqual(plan, 0)
        self.assertGreater(telemetry, plan)
        self.assertIn("atomic_store(&g_thermal", body[telemetry:])
        self.assertEqual(self.source.count("fge_telemetry("), 1)

    def test_stopping_the_engine_clears_the_sample(self):
        body = function_body(self.source, "stop_engine")
        self.assertIn("fge_stop()", body)
        self.assertIn("atomic_store(&g_thermal, -1.0f)", body)


if __name__ == "__main__":
    unittest.main()
