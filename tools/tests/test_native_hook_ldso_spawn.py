import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

from tools.tests.test_usr2_preload import runner

HOOK_BUILD = Path(__file__).resolve().parents[1] / "nativeguest/build.sh"
GUEST_LDSO = "/usr/lib/ld-linux-aarch64.so.1"
GUEST_TOOL = "/usr/local/bin/tool"
BOUND_DIR = "/opt/runtime"
HOST_SHELL = "/data/local/tmp/droiddeck-hook-probe-sh"

RECORDER = r"""
#define _GNU_SOURCE
#include <spawn.h>
#include <stdio.h>
#include <string.h>
#include <unistd.h>

static void show(const char *path, char *const argv[], char *const envp[]) {
  printf("call\t%s", path);
  for (int i = 0; argv[i]; i++) printf("\t%s", argv[i]);
  printf("\n");
  for (int i = 0; envp && envp[i]; i++)
    if (strncmp(envp[i], "DDNATV_LIBRARY_PATH=", 20) == 0) printf("libpath\t%s\n", envp[i] + 20);
  fflush(stdout);
}

int posix_spawn(pid_t *pid, const char *path, const posix_spawn_file_actions_t *actions,
                const posix_spawnattr_t *attr, char *const argv[], char *const envp[]) {
  show(path, argv, envp);
  if (pid) *pid = 1;
  return 0;
}

int execve(const char *path, char *const argv[], char *const envp[]) {
  show(path, argv, envp);
  _exit(0);
}
"""

PROBE = r"""
#include <spawn.h>
#include <stdio.h>
#include <string.h>
#include <unistd.h>

extern char **environ;

int main(int argc, char **argv) {
  if (argc < 3) return 2;
  if (strcmp(argv[1], "spawn") == 0) {
    pid_t pid;
    posix_spawn_file_actions_t actions;
    posix_spawn_file_actions_init(&actions);
    posix_spawn_file_actions_adddup2(&actions, 1, 1);
    int ret = posix_spawn(&pid, argv[2], &actions, NULL, argv + 2, environ);
    posix_spawn_file_actions_destroy(&actions);
    printf("ret\t%d\n", ret);
    return 0;
  }
  execve(argv[2], argv + 2, environ);
  printf("ret\t-1\n");
  return 1;
}
"""


def guest_loader(prefix):
    roots = [Path(prefix[prefix.index("-L") + 1])] if prefix and "-L" in prefix else [Path("/")]
    for root in roots:
        for name in ("lib/ld-linux-aarch64.so.1", "lib64/ld-linux-aarch64.so.1", "usr/lib/ld-linux-aarch64.so.1"):
            if (root / name).exists():
                return root / name
    return None


class NativeHookLdsoSpawnTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.cc, cls.prefix = runner()
        if cls.cc is None:
            raise unittest.SkipTest("no aarch64 compiler and runner")
        loader = guest_loader(cls.prefix)
        if loader is None:
            raise unittest.SkipTest("no aarch64 glibc loader to put in the rootfs")
        cls.tmp = tempfile.TemporaryDirectory()
        tmp = Path(cls.tmp.name)
        cls.hook = tmp / "hook.so"
        subprocess.run(["bash", str(HOOK_BUILD), str(cls.hook), cls.cc], check=True, capture_output=True)
        (tmp / "recorder.c").write_text(RECORDER)
        cls.recorder = tmp / "recorder.so"
        subprocess.run([cls.cc, "-shared", "-fPIC", "-O1", "-o", str(cls.recorder), str(tmp / "recorder.c")], check=True)
        (tmp / "probe.c").write_text(PROBE)
        cls.probe = tmp / "probe"
        subprocess.run([cls.cc, "-O1", "-o", str(cls.probe), str(tmp / "probe.c")], check=True)
        cls.root = tmp / "root"
        for guest, source in ((GUEST_LDSO, loader), (GUEST_TOOL, cls.probe)):
            host = cls.root / guest.lstrip("/")
            host.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy(source, host)
            host.chmod(0o755)
        (cls.root / "usr/lib/aarch64-linux-gnu").mkdir(parents=True, exist_ok=True)
        cls.runtime = tmp / "runtime"
        cls.runtime.mkdir()
        shutil.copy(cls.probe, cls.runtime / "bash")
        (cls.runtime / "bash").chmod(0o755)
        for name, interpreter in (("bound.sh", f"{BOUND_DIR}/bash"), ("host.sh", HOST_SHELL)):
            script = cls.root / "opt" / name
            script.parent.mkdir(exist_ok=True)
            script.write_text(f"#!{interpreter}\n")
            script.chmod(0o755)

    @classmethod
    def tearDownClass(cls):
        cls.tmp.cleanup()

    def call(self, how, *argv, binds=None):
        hook_env = {"DDNATV_ROOT": str(self.root), "LD_PRELOAD": f"{self.hook}:{self.recorder}"}
        if binds:
            hook_env["DDNATV_BINDS"] = binds
        env = {k: v for k, v in os.environ.items() if k not in ("LD_PRELOAD", "DDNATV_EXEC", "LD_LIBRARY_PATH")}
        if self.prefix:
            cmd = list(self.prefix) + [arg for key, value in hook_env.items() for arg in ("-E", f"{key}={value}")]
        else:
            cmd = []
            env.update(hook_env)
        out = subprocess.run(cmd + [str(self.probe), how, *argv], env=env, capture_output=True, text=True, timeout=120)
        calls = [line.split("\t")[1:] for line in out.stdout.splitlines() if line.startswith("call\t")]
        self.library_paths = [line.split("\t", 1)[1] for line in out.stdout.splitlines() if line.startswith("libpath\t")]
        self.assertEqual(1, len(calls), out.stdout + out.stderr)
        if how == "spawn":
            self.assertIn("ret\t0", out.stdout.splitlines(), out.stdout + out.stderr)
        return calls[0]

    def test_spawned_ldso_list_loads_the_program_from_the_rootfs(self):
        call = self.call("spawn", GUEST_LDSO, "--list", GUEST_TOOL)
        argv = call[1:]
        self.assertEqual(str(self.root / GUEST_TOOL.lstrip("/")), argv[-1])
        self.assertEqual("--list", argv[-2])
        self.assertIn("--library-path", argv[:-2])

    def test_spawn_and_exec_start_ldso_with_the_same_arguments(self):
        for args in (("--list", GUEST_TOOL), (GUEST_TOOL, "x"), ("--library-path", "/opt/own", "--list", GUEST_TOOL)):
            with self.subTest(args=args):
                self.assertEqual(self.call("exec", GUEST_LDSO, *args), self.call("spawn", GUEST_LDSO, *args))

    def test_a_callers_own_library_path_is_kept(self):
        call = self.call("spawn", GUEST_LDSO, "--library-path", "/opt/own", "--list", GUEST_TOOL)
        self.assertEqual([GUEST_LDSO, "--library-path", "/opt/own", "--list", str(self.root / GUEST_TOOL.lstrip("/"))], call[1:])

    def test_ldso_without_a_program_is_spawned_unchanged(self):
        call = self.call("spawn", GUEST_LDSO, "--version")
        self.assertEqual([GUEST_LDSO, "--version"], call[1:])

    def test_spawn_and_exec_start_a_dynamic_program_the_same_way(self):
        self.assertEqual(self.call("exec", GUEST_TOOL, "x"), self.call("spawn", GUEST_TOOL, "x"))

    def test_a_program_started_through_ldso_records_its_library_path(self):
        for how in ("exec", "spawn"):
            with self.subTest(how=how):
                argv = self.call(how, GUEST_TOOL, "x")[1:]
                self.assertEqual([argv[argv.index("--library-path") + 1]], self.library_paths)

    def test_ldso_started_by_name_records_no_library_path(self):
        for how in ("exec", "spawn"):
            with self.subTest(how=how):
                self.call(how, GUEST_LDSO, "--list", GUEST_TOOL)
                self.assertEqual([], self.library_paths)

    def test_a_script_interpreter_from_a_bind_runs_as_a_guest_program(self):
        for how in ("exec", "spawn"):
            with self.subTest(how=how):
                call = self.call(how, "/opt/bound.sh", "x", binds=f"{self.runtime}:{BOUND_DIR}")
                self.assertEqual(str(self.root / GUEST_LDSO.lstrip("/")), call[0])
                self.assertEqual([str(self.runtime / "bash"), str(self.root / "opt/bound.sh"), "x"], call[-3:])

    def test_a_script_interpreter_outside_the_rootfs_and_its_binds_stays_on_the_host(self):
        for how in ("exec", "spawn"):
            with self.subTest(how=how):
                call = self.call(how, "/opt/host.sh", "x", binds=f"{self.runtime}:{BOUND_DIR}")
                self.assertEqual([HOST_SHELL, HOST_SHELL, str(self.root / "opt/host.sh"), "x"], call)


if __name__ == "__main__":
    unittest.main()
