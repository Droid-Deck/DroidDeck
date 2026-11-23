import os
from pathlib import Path
import subprocess
import tempfile
import unittest

from tools.tests.test_usr2_preload import runner

REPO = Path(__file__).resolve().parents[2]
HOOK_BUILD = REPO / "tools/nativeguest/build.sh"
DDEXEC = REPO / "app/src/main/cpp/ddexec/ddexec.c"
DDEXEC_FLAGS = ["-shared", "-fPIC", "-O2", "-ffreestanding", "-fno-builtin", "-fno-stack-protector",
                "-fno-asynchronous-unwind-tables", "-nostdlib", "-Wl,-e,_start", "-Wl,-z,noexecstack",
                "-Wl,--no-undefined", "-Wl,-Bsymbolic"]

WINDOWS_PROGRAM = "C:\\Program Files (x86)\\Battle.net\\Battle.net.exe"

PROBE = r"""
#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <string.h>
#include <sys/syscall.h>
#include <unistd.h>

static void show_cmdline(const char *label) {
  static char buf[1 << 20];
  int fd = (int)syscall(SYS_openat, AT_FDCWD, "/proc/self/cmdline", O_RDONLY);
  ssize_t len = 0, n;
  while (fd >= 0 && (n = read(fd, buf + len, sizeof(buf) - len)) > 0) len += n;
  if (fd >= 0) close(fd);
  while (len > 0 && buf[len - 1] == '\0') len--;
  printf("%s", label);
  for (ssize_t at = 0; at < len; at += (ssize_t)strlen(buf + at) + 1) printf("\t%s", buf + at);
  printf("\n");
}

int main(int argc, char **argv) {
  int contiguous = 1;
  for (int i = 1; i < argc; i++) {
    if (argv[i] != argv[i - 1] + strlen(argv[i - 1]) + 1) contiguous = 0;
  }
  printf("contiguous\t%d\n", contiguous);
  printf("argv");
  for (int i = 0; i < argc; i++) printf("\t%s", argv[i]);
  printf("\n");
  printf("name\t%s\t%s\n", program_invocation_name, program_invocation_short_name);
  show_cmdline("cmdline");
  if (argc > 1 && contiguous) {
    size_t offset = (size_t)(argv[1] - argv[0]);
    char *end = argv[argc - 1] + strlen(argv[argc - 1]) + 1;
    memmove(argv[0], argv[1], (size_t)(end - argv[1]));
    memset(end - offset, 0, offset);
    show_cmdline("shifted");
  }
  return 0;
}
"""


class NativeHookCmdlineTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.cc, cls.prefix = runner()
        if cls.cc is None:
            raise unittest.SkipTest("no aarch64 compiler and runner")
        cls.tmp = tempfile.TemporaryDirectory()
        tmp = Path(cls.tmp.name)
        cls.hook = tmp / "hook.so"
        subprocess.run(["bash", str(HOOK_BUILD), str(cls.hook), cls.cc], check=True, capture_output=True)
        (tmp / "probe.c").write_text(PROBE)
        cls.probe = tmp / "probe"
        subprocess.run([cls.cc, "-O1", "-o", str(cls.probe), str(tmp / "probe.c")], check=True)
        cls.ddexec = tmp / "lib/libddexec.so"
        cls.ddexec.parent.mkdir()
        subprocess.run([cls.cc, *DDEXEC_FLAGS, "-o", str(cls.ddexec), str(DDEXEC)], check=True)
        cls.ddexec_runs = subprocess.run(list(cls.prefix) + [str(cls.ddexec), str(cls.probe), str(cls.probe)],
                                         capture_output=True, timeout=120).returncode == 0
        cls.root = tmp / "root"
        cls.root.mkdir()
        if cls.prefix:
            libs = Path(cls.prefix[cls.prefix.index("-L") + 1]) / "lib"
        else:
            libs = next(Path(p) for p in ("/lib/aarch64-linux-gnu", "/usr/lib/aarch64-linux-gnu", "/lib64")
                        if (Path(p) / "libc.so.6").exists())
        cls.ld_so = next(str(p) for p in (libs / "ld-linux-aarch64.so.1", Path("/lib/ld-linux-aarch64.so.1"))
                         if p.exists())
        cls.libs = str(libs)

    @classmethod
    def tearDownClass(cls):
        cls.tmp.cleanup()

    def run_probe(self, argv, root=True, preload=False):
        env = {k: v for k, v in os.environ.items() if k not in ("LD_PRELOAD", "DDNATV_ROOT", "DDNATV_REAL_EXE")}
        if root:
            env["DDNATV_ROOT"] = str(self.root)
        runner_args = list(self.prefix)
        if preload and self.prefix:
            runner_args += ["-E", f"LD_PRELOAD={self.hook}"]
        elif preload:
            env["LD_PRELOAD"] = str(self.hook)
        out = subprocess.run(runner_args + argv, env=env, capture_output=True, text=True, timeout=120)
        self.assertEqual(out.returncode, 0, out.stderr)
        return {line.split("\t")[0]: line.split("\t")[1:] for line in out.stdout.splitlines()}

    def loader_argv(self, *args, name="/usr/bin/wine"):
        named = ["--argv0", name] if name else []
        return [self.ld_so, "--preload", str(self.hook), "--library-path", self.libs, *named, str(self.probe), *args]

    def test_a_program_its_loader_starts_has_its_arguments_together(self):
        seen = self.run_probe(self.loader_argv(WINDOWS_PROGRAM, "--in-tray"))
        self.assertEqual(["/usr/bin/wine", WINDOWS_PROGRAM, "--in-tray"], seen["argv"])
        self.assertEqual(["1"], seen["contiguous"])
        self.assertEqual(["/usr/bin/wine", "wine"], seen["name"])

    @unittest.skipIf(runner()[1], "qemu answers /proc/self/cmdline from its own copy of the arguments")
    def test_the_command_line_shows_the_arguments_the_program_was_started_with(self):
        seen = self.run_probe(self.loader_argv(WINDOWS_PROGRAM, "--in-tray"))
        self.assertEqual(["/usr/bin/wine", WINDOWS_PROGRAM, "--in-tray"], seen["cmdline"])
        self.assertEqual([WINDOWS_PROGRAM, "--in-tray"], seen["shifted"])

    @unittest.skipIf(runner()[1], "qemu answers /proc/self/cmdline from its own copy of the arguments")
    def test_the_app_loader_in_front_of_the_loader_is_left_out(self):
        if not self.ddexec_runs:
            self.skipTest("libddexec.so does not start a program on this host")
        seen = self.run_probe([str(self.ddexec), self.ld_so] + self.loader_argv(WINDOWS_PROGRAM))
        self.assertEqual(["/usr/bin/wine", WINDOWS_PROGRAM], seen["argv"])
        self.assertEqual(["/usr/bin/wine", WINDOWS_PROGRAM], seen["cmdline"])
        self.assertEqual([WINDOWS_PROGRAM], seen["shifted"])

    def test_a_loader_run_without_a_program_name_keeps_its_command_line(self):
        argv = self.loader_argv(WINDOWS_PROGRAM, name=None)
        seen = self.run_probe(argv)
        self.assertEqual([str(self.probe), WINDOWS_PROGRAM], seen["argv"])
        self.assertEqual(["1"], seen["contiguous"])
        if not self.prefix:
            self.assertEqual(argv, seen["cmdline"])

    def test_outside_the_native_userland_the_command_line_is_unchanged(self):
        argv = self.loader_argv(WINDOWS_PROGRAM)
        seen = self.run_probe(argv, root=False)
        self.assertEqual(["/usr/bin/wine", WINDOWS_PROGRAM], seen["argv"])
        self.assertEqual(["0"], seen["contiguous"])
        if not self.prefix:
            self.assertEqual(argv, seen["cmdline"])

    def test_a_program_started_directly_is_unchanged(self):
        seen = self.run_probe([str(self.probe), WINDOWS_PROGRAM, "--in-tray"], preload=True)
        self.assertEqual([str(self.probe), WINDOWS_PROGRAM, "--in-tray"], seen["argv"])
        self.assertEqual(["1"], seen["contiguous"])
        if not self.prefix:
            self.assertEqual([str(self.probe), WINDOWS_PROGRAM, "--in-tray"], seen["cmdline"])


if __name__ == "__main__":
    unittest.main()
