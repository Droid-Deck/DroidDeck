from pathlib import Path
import os
import subprocess
import tempfile
import unittest

from tools.tests.test_usr2_preload import runner

HOOK_BUILD = Path(__file__).resolve().parents[1] / "nativeguest/build.sh"

PROBE = r"""
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <semaphore.h>
#include <stdio.h>
#include <sys/wait.h>
#include <unistd.h>

static void show(const char *name, int ok) { printf("%s\t%d\n", name, ok); fflush(stdout); }

int main(int argc, char **argv) {
  const char *name = argv[1];
  sem_t *sem = sem_open(name, O_CREAT | O_EXCL, 0600, 1);
  show("create", sem != SEM_FAILED);
  show("exclusive", sem_open(name, O_CREAT | O_EXCL, 0600, 1) == SEM_FAILED && errno == EEXIST);
  sem_t *again = sem_open(name, O_CREAT, 0600, (unsigned int)SEM_VALUE_MAX + 1);
  int value = -1;
  show("existing", again == sem && sem_getvalue(again, &value) == 0 && value == 1);
  show("too-large", sem_open("/ddsem-absent", O_CREAT, 0600, (unsigned int)SEM_VALUE_MAX + 1) == SEM_FAILED && errno == EINVAL);
  show("wait", sem_wait(sem) == 0 && sem_trywait(again) == -1 && errno == EAGAIN);
  pid_t child = fork();
  if (child == 0) {
    sem_t *other = sem_open(name, 0);
    _exit(other != SEM_FAILED && sem_post(other) == 0 && sem_close(other) == 0 ? 0 : 1);
  }
  int status = 0;
  waitpid(child, &status, 0);
  show("shared", WIFEXITED(status) && WEXITSTATUS(status) == 0 && sem_trywait(sem) == 0);
  show("close", sem_close(again) == 0 && sem_post(sem) == 0 && sem_close(sem) == 0 && sem_close(sem) == -1 && errno == EINVAL);
  show("unlink", sem_unlink(name) == 0);
  show("gone", sem_open(name, 0) == SEM_FAILED && errno == ENOENT);
  return 0;
}
"""

KEEP = r"""
#include <fcntl.h>
#include <semaphore.h>
int main(int argc, char **argv) { return sem_open(argv[1], O_CREAT | O_EXCL, 0640, 3) == SEM_FAILED; }
"""

FORKER = r"""
#include <fcntl.h>
#include <pthread.h>
#include <semaphore.h>
#include <stdio.h>
#include <sys/wait.h>
#include <unistd.h>

static const char *name;
static volatile int done;

static void *churn(void *arg) {
  while (!done) {
    sem_t *sem = sem_open(name, O_CREAT, 0600, 0);
    if (sem != SEM_FAILED) sem_close(sem);
  }
  return arg;
}

int main(int argc, char **argv) {
  name = argv[1];
  pthread_t thread;
  pthread_create(&thread, NULL, churn, NULL);
  int finished = 0;
  for (int i = 0; i < 200; i++) {
    pid_t pid = fork();
    if (pid == 0) {
      alarm(10);
      sem_t *sem = sem_open(name, O_CREAT, 0600, 0);
      _exit(sem == SEM_FAILED || sem_close(sem) != 0);
    }
    int status;
    if (waitpid(pid, &status, 0) == pid && WIFEXITED(status) && WEXITSTATUS(status) == 0) finished++;
  }
  done = 1;
  pthread_join(thread, NULL);
  sem_unlink(name);
  printf("finished\t%d\n", finished);
  return 0;
}
"""

CALLS = ("create", "exclusive", "existing", "too-large", "wait", "shared", "close", "unlink", "gone")


class NativeHookNamedSemaphoresTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.cc, cls.prefix = runner()
        if cls.cc is None:
            raise unittest.SkipTest("no aarch64 compiler and runner")
        cls.tmp = tempfile.TemporaryDirectory(dir="/tmp")
        tmp = Path(cls.tmp.name)
        cls.hook = tmp / "hook.so"
        subprocess.run(["bash", str(HOOK_BUILD), str(cls.hook), cls.cc], check=True, capture_output=True)
        (tmp / "probe.c").write_text(PROBE)
        cls.probe = tmp / "probe"
        subprocess.run([cls.cc, "-O1", "-pthread", "-o", str(cls.probe), str(tmp / "probe.c")], check=True)
        cls.shm = tmp / "root/tmp/shm"
        cls.shm.mkdir(parents=True)

    @classmethod
    def tearDownClass(cls):
        cls.tmp.cleanup()

    def run_hooked(self, program, *args):
        hook_env = {"DDNATV_ROOT": str(self.shm.parent.parent), "LD_PRELOAD": str(self.hook)}
        env = {k: v for k, v in os.environ.items() if k != "LD_PRELOAD"}
        if self.prefix:
            cmd = list(self.prefix) + [arg for key, value in hook_env.items() for arg in ("-E", f"{key}={value}")]
        else:
            cmd = []
            env.update(hook_env)
        out = subprocess.run(cmd + [str(program), *args], env=env, capture_output=True, text=True, timeout=120)
        self.assertEqual(out.returncode, 0, out.stderr)
        return dict(line.split("\t") for line in out.stdout.splitlines())

    def test_a_named_semaphore_lives_in_the_rootfs_and_is_shared_between_processes(self):
        name = f"/ddsem-{os.getpid()}"
        self.assertEqual({call: "1" for call in CALLS}, self.run_hooked(self.probe, name))
        self.assertFalse(Path(f"/dev/shm/sem.{name[1:]}").exists())
        self.assertEqual([], list(self.shm.iterdir()))

    def test_a_child_forked_while_another_thread_opens_a_semaphore_can_open_one(self):
        source = self.probe.with_name("forker.c")
        source.write_text(FORKER)
        forker = self.probe.with_name("forker")
        subprocess.run([self.cc, "-O1", "-pthread", "-o", str(forker), str(source)], check=True)
        self.assertEqual({"finished": "200"}, self.run_hooked(forker, f"/ddsem-fork-{os.getpid()}"))

    def test_a_created_semaphore_keeps_its_name_and_mode(self):
        name = f"ddsem-kept-{os.getpid()}"
        source = self.probe.with_name("keep.c")
        source.write_text(KEEP)
        keep = self.probe.with_name("keep")
        subprocess.run([self.cc, "-O1", "-pthread", "-o", str(keep), str(source)], check=True)
        self.run_hooked(keep, name)
        made = self.shm / f"sem.{name}"
        self.addCleanup(made.unlink)
        umask = os.umask(0)
        os.umask(umask)
        self.assertEqual([made.name], [p.name for p in self.shm.iterdir()])
        self.assertEqual(0o640 & ~umask, made.stat().st_mode & 0o777)

if __name__ == "__main__":
    unittest.main()
