from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

SOURCE = Path(__file__).resolve().parents[1] / "linuxfs/preload/oomadj.c"

PROGRAM = r"""
#include <stdio.h>
const char *bl_oom_adj_redirect(const char *path, int writing);
int main(int argc, char **argv) {
  for (int i = 1; i + 1 < argc; i += 2) printf("%s\n", bl_oom_adj_redirect(argv[i + 1], argv[i][0] == 'w'));
  return 0;
}
"""


@unittest.skipUnless(shutil.which("cc"), "needs cc")
class OomAdjPreloadTest(unittest.TestCase):
    def test_only_writes_to_oom_files_go_to_dev_null(self):
        with tempfile.TemporaryDirectory() as tmp:
            main = Path(tmp) / "main.c"
            main.write_text(PROGRAM)
            binary = Path(tmp) / "probe"
            subprocess.run(["cc", "-o", str(binary), str(main), str(SOURCE)], check=True)
            cases = [
                ("w", "/proc/self/oom_score_adj", "/dev/null"),
                ("w", "/proc/123/oom_score_adj", "/dev/null"),
                ("w", "/proc/thread-self/oom_score_adj", "/dev/null"),
                ("w", "/proc/12/task/34/oom_score_adj", "/dev/null"),
                ("w", "/proc/self/oom_adj", "/dev/null"),
                ("r", "/proc/self/oom_score_adj", "/proc/self/oom_score_adj"),
                ("w", "/proc/self/oom_score", "/proc/self/oom_score"),
                ("w", "/proc/abc/oom_score_adj", "/proc/abc/oom_score_adj"),
                ("w", "/tmp/oom_score_adj", "/tmp/oom_score_adj"),
            ]
            args = [str(binary)]
            for mode, path, _ in cases:
                args += [mode, path]
            out = subprocess.run(args, capture_output=True, text=True, check=True)
            self.assertEqual(out.stdout.split(), [want for _, _, want in cases])
            self.assertIn("oom_score_adj raise was dropped", out.stderr)


if __name__ == "__main__":
    unittest.main()
