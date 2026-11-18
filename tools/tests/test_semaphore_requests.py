import platform
from pathlib import Path
import runpy
import shutil
import subprocess
import tempfile
import unittest

ESYNC = Path(__file__).resolve().parents[1] / "linuxfs/overlay/usr/local/bin/droiddeck-esync"


def server_source(names):
    table = ",\n".join('  "%s"' % name for name in names)
    return """
#include <stdio.h>
const char *const req_names[] = {
%s
};
int main(int argc, char **argv) { puts(req_names[argc %% %d]); return 0; }
""" % (table, len(names))


def request_names(create=40, close=21, count=150):
    names = ["new_process"] + ["request_%d" % n for n in range(1, count)]
    names[create] = "create_semaphore"
    names[close] = "close_handle"
    return names


@unittest.skipUnless(platform.machine() in ("aarch64", "arm64") and shutil.which("gcc"), "needs an arm64 gcc")
class SemaphoreRequestsTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.find = staticmethod(runpy.run_path(str(ESYNC))["semaphore_requests"])
        cls.tmp = tempfile.TemporaryDirectory()

    @classmethod
    def tearDownClass(cls):
        cls.tmp.cleanup()

    def build(self, name, names):
        source = Path(self.tmp.name) / (name + ".c")
        binary = Path(self.tmp.name) / name
        source.write_text(server_source(names))
        subprocess.run(["gcc", "-O2", "-fPIE", "-pie", "-o", str(binary), str(source)], check=True)
        return binary

    def test_reads_both_request_numbers_from_the_name_table(self):
        self.assertEqual(self.find(str(self.build("wineserver", request_names()))), "40,21")

    def test_other_positions_are_read_as_they_are(self):
        self.assertEqual(self.find(str(self.build("moved", request_names(create=57, close=3)))), "57,3")

    def test_a_short_table_or_missing_request_gives_nothing(self):
        self.assertIsNone(self.find(str(self.build("short", request_names(count=60)))))
        names = request_names()
        names[21] = "request_21"
        self.assertIsNone(self.find(str(self.build("noclose", names))))

    def test_a_file_that_is_not_elf_gives_nothing(self):
        path = Path(self.tmp.name) / "script"
        path.write_text("#!/bin/sh\n")
        self.assertIsNone(self.find(str(path)))
        self.assertIsNone(self.find(str(Path(self.tmp.name) / "missing")))


if __name__ == "__main__":
    unittest.main()
