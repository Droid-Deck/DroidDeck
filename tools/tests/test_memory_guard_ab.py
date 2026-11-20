from pathlib import Path
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import memory_guard_ab as ab


ACTIVITIES = """\
Display #2 (activities from top to bottom):
  * Task{b24f3e8 #1421 type=standard A=10120:com.droiddeck.launcher.keeptop U=0 visible=true visibleRequested=true mode=fullscreen translucent=true sz=1}
    * Hist  #0: ActivityRecord{10082571 u0 com.droiddeck.launcher/.session.KeepTopActivity t1421}
  * Task{3002b72 #6 type=home U=0 visible=true visibleRequested=true mode=fullscreen translucent=false sz=2}
    * Task{b347c40 #7 type=home A=10082:com.android.launcher3 nonResizable U=0 rootTaskId=6 visible=false visibleRequested=false mode=fullscreen translucent=true sz=1}
    * Task{1ea585a #1448 type=home A=10130:rip.moth.cocoonshell nonResizable U=0 rootTaskId=6 visible=true visibleRequested=true mode=fullscreen translucent=false sz=1}

Display #0 (activities from top to bottom):
  * Task{972c91 #1434 type=standard A=10120:com.droiddeck.launcher U=0 visible=true visibleRequested=true mode=fullscreen translucent=false sz=2}
    * Hist  #1: ActivityRecord{240807202 u0 com.droiddeck.launcher/.session.KeepTopActivity t1633}
    * Hist  #0: ActivityRecord{126627493 u0 com.droiddeck.launcher/.SessionActivity t1434}
  * Task{93ad796 #1 type=home U=0 visible=false visibleRequested=false mode=fullscreen translucent=true sz=1}
    * Task{ae41383 #1408 type=home I=com.android.launcher3/.uioverrides.QuickstepLauncher U=0 rootTaskId=1 visible=true visibleRequested=true mode=fullscreen translucent=true sz=1}
"""

WINDOWS = """\
  Window #19 Window{6dc6ad8 u0 NavigationBar2}:
    mDisplayId=2 rootTaskId=1 mSession=Session{1 2:u0a1} mClient=android.os.BinderProxy@1
  Window #20 Window{cde10e4 u0 com.droiddeck.launcher}:
    mDisplayId=2 rootTaskId=1 mSession=Session{2 3:u0a120} mClient=android.os.BinderProxy@2
  Window #21 Window{d8e62b4 u0 com.droiddeck.launcher/com.droiddeck.launcher.session.KeepTopActivity}:
    mDisplayId=2 taskId=1425 mSession=Session{2 3:u0a120} mClient=android.os.BinderProxy@3
  Window #22 Window{aeb9be7 u0 com.droiddeck.launcher}:
    mDisplayId=0 rootTaskId=1 mSession=Session{2 3:u0a120} mClient=android.os.BinderProxy@4
"""

DISPLAYS = """\
  Display: mDisplayId=2 (organized)
    init=1280x960 320dpi mMinSizeOfResizeableTaskDp=220 cur=1280x960 app=1280x960 rng=960x960-1280x1280
  Display: mDisplayId=0 (organized)
    init=1080x1920 369dpi mMinSizeOfResizeableTaskDp=220 cur=1920x1080 app=1920x1080 rng=1080x1080-1920x1920
"""

EVENTS = """\
11-19 22:50:25.389   514   514 I killinfo: [28834,10129,995,50,95820,1,28492]
11-19 22:50:26.239   514   514 I killinfo: [29132,1000,102,50,84792,1,43120]
11-19 22:50:33.400   514   514 I killinfo: [25557,10120,0,0,94252,1,79084]
11-19 22:50:29.811  2034  3827 I am_proc_died: [0,31749,rip.moth.cocoonshell,102,2]
11-19 22:50:30.199  2034  3827 I am_proc_died: [0,2286,rip.moth.cocoonshell,102,2]
11-19 22:50:33.535  2034 10103 I am_proc_died: [0,25557,com.droiddeck.launcher,0,2]
11-19 22:50:34.551  2034 11587 I wm_task_to_front: [0,1408,0]
11-19 22:42:44.766  2034  2707 I wm_create_activity: [0,1239004,1628,com.droiddeck.launcher/.session.KeepTopActivity,NULL,NULL,NULL,411107328]
11-19 22:42:44.816 16856 16856 I wm_on_top_resumed_lost_called: [24689759,com.droiddeck.launcher.SessionActivity,topStateChangedWhenResumed]
11-19 22:42:44.817 16856 16856 I wm_on_top_resumed_lost_called: [24689760,com.droiddeck.launcher.MainActivity,pausing]
"""


class DumpParsingTest(unittest.TestCase):
    def test_focused_display(self):
        self.assertEqual(2, ab.focused_display("  FocusedDisplayId: 2\n  FocusedApplications:\n"))
        self.assertIsNone(ab.focused_display(""))

    def test_guards_and_sessions_are_counted_on_every_display(self):
        self.assertEqual(2, ab.guard_count(ACTIVITIES))
        self.assertEqual(1, ab.session_count(ACTIVITIES))

    def test_the_bottom_home_is_the_visible_home_on_another_display(self):
        self.assertEqual((2, "rip.moth.cocoonshell"), ab.bottom_home(ACTIVITIES, 0))

    def test_android_13_activity_records_are_counted(self):
        self.assertEqual(1, ab.session_count(
            "    * Hist  #1: ActivityRecord{e1ec207 u0 com.droiddeck.launcher/.SessionActivity} t14519}\n"))

    def test_the_visible_app_stands_in_when_no_home_is_on_the_other_display(self):
        dump = ("Display #4 (activities from top to bottom):\n"
                "  * Task{21f60d6 #14493 type=standard A=10113:com.discord U=0 visible=true visibleRequested=true mode=fullscreen}\n")
        self.assertEqual((4, "com.discord"), ab.bottom_home(dump, 0))

    def test_no_bottom_home_without_another_display(self):
        self.assertIsNone(ab.bottom_home(ACTIVITIES.split("\n\n")[1], 0))

    def test_panel_windows_are_the_apps_bare_windows_on_that_display(self):
        self.assertEqual(1, ab.panel_windows(WINDOWS, 2))
        self.assertEqual(1, ab.panel_windows(WINDOWS, 0))
        self.assertEqual(0, ab.panel_windows(WINDOWS, 3))

    def test_logical_size_follows_rotation(self):
        self.assertEqual((1920, 1080), ab.logical_size(DISPLAYS, 0))
        self.assertEqual((1280, 960), ab.logical_size(DISPLAYS, 2))
        self.assertIsNone(ab.logical_size(DISPLAYS, 5))


class HogScriptTest(unittest.TestCase):
    def test_both_modes_start_the_fill_counter(self):
        script = ab.HOG.splitlines()
        counter = next(n for n, line in enumerate(script) if line.startswith("i=0"))
        self.assertLess(counter, script.index('if [ "$mode" = burst ]; then'))
        self.assertLess(counter, next(n for n, line in enumerate(script) if line.startswith("while [ $i -lt 512 ]")))


    def test_a_failed_write_never_reports_released(self):
        script = ab.HOG.splitlines()
        check = script.index('[ "$failed" = 0 ] || { echo "write failed"; cleanup; exit 1; }')
        self.assertLess(check, script.index("echo released"))
        self.assertIn("failed=1", ab.HOG.split("echo released")[0])

    def test_a_burst_takes_the_remainder_below_one_chunk(self):
        self.assertIn("rest=$((take % 256))", ab.HOG)
        self.assertIn('echo "nothing to take"', ab.HOG)


class SummaryTest(unittest.TestCase):
    def test_max_adj_skips_samples_after_death(self):
        self.assertEqual(50, ab.max_adj(["0", "50", "DEAD", ""]))
        self.assertIsNone(ab.max_adj(["DEAD"]))

    def test_events_summary(self):
        summary = ab.summarize_events(EVENTS)
        self.assertEqual(3, summary["lmkd_kills"])
        self.assertEqual(1, summary["lmkd_kills_down_to_adj0"])
        self.assertEqual({"com.droiddeck.launcher": 1, "rip.moth.cocoonshell": 2}, summary["processes_died"])
        self.assertEqual(0, summary["droiddeck_died_at_adj"])
        self.assertEqual(1, summary["session_top_lost"])
        self.assertEqual(1, summary["task_to_front"])
        self.assertEqual(1, summary["guard_creates"])

    def test_a_quiet_log_summarizes_to_nothing(self):
        summary = ab.summarize_events("")
        self.assertEqual(0, summary["lmkd_kills"])
        self.assertIsNone(summary["droiddeck_died_at_adj"])
        self.assertEqual({}, summary["processes_died"])


if __name__ == "__main__":
    unittest.main()
