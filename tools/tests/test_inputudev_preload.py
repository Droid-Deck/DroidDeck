from pathlib import Path
import os
import shutil
import subprocess
import sys
import tempfile
import unittest

PRELOAD = Path(__file__).resolve().parents[1] / "linuxfs/preload"

ONCE = r"""
#define _GNU_SOURCE
#include <dlfcn.h>
#include <pthread.h>
#include <sys/syscall.h>
#include <unistd.h>

static pthread_once_t once = PTHREAD_ONCE_INIT;
static long (*next_syscall)(long, ...);

static void init(void) { next_syscall = (long (*)(long, ...))dlsym(RTLD_NEXT, "syscall"); }

ssize_t readlink(const char *path, char *buf, size_t size) {
  pthread_once(&once, init);
  return next_syscall(SYS_readlinkat, -100, path, buf, size);
}

__attribute__((constructor)) static void start(void) {
  char link[4096];
  readlink("/proc/self/exe", link, sizeof(link));
}
"""

FIRST = r"""
#define _GNU_SOURCE
#include <dlfcn.h>
#include <stddef.h>

int marker(void) { return 1; }

int next_marker(void) {
  int (*next)(void) = (int (*)(void))dlsym(RTLD_NEXT, "marker");
  return next != NULL ? next() : 0;
}
"""

SECOND = "int marker(void) { return 2; }\n"

CHAIN = r"""
#define _GNU_SOURCE
#include <dlfcn.h>
#include <stddef.h>

static int calls;

int chained(void) { return calls > 1; }

void *dlsym(void *handle, const char *name) {
  static void *(*real)(void *, const char *);
  if (real == NULL) real = (void *(*)(void *, const char *))dlvsym(RTLD_NEXT, "dlsym", "GLIBC_2.2.5");
  if (real == NULL) real = (void *(*)(void *, const char *))dlvsym(RTLD_NEXT, "dlsym", "GLIBC_2.17");
  if (real == NULL) real = (void *(*)(void *, const char *))dlvsym(RTLD_NEXT, "dlsym", "GLIBC_2.34");
  calls++;
  return real(handle, name);
}
"""

UDEV = r"""
#include <stdlib.h>

struct list { const char *name; };
struct enumerate { struct list head; };

void *udev_enumerate_new(void *udev) {
  static int count;
  static const char *names[] = {"real1", "real2"};
  struct enumerate *enumerate = calloc(1, sizeof(*enumerate));
  (void)udev;
  enumerate->head.name = names[count++ % 2];
  return enumerate;
}
void *udev_enumerate_unref(void *enumerate) { free(enumerate); return NULL; }
int udev_enumerate_add_match_subsystem(void *enumerate, const char *subsystem) { (void)enumerate; (void)subsystem; return 0; }
int udev_enumerate_scan_devices(void *enumerate) { (void)enumerate; return 0; }
void *udev_enumerate_get_list_entry(void *enumerate) { return &((struct enumerate *)enumerate)->head; }
void *udev_list_entry_get_next(void *entry) { (void)entry; return NULL; }
void *udev_list_entry_get_by_name(void *entry, const char *name) { (void)name; return entry; }
const char *udev_list_entry_get_name(void *entry) { return ((struct list *)entry)->name; }
void *udev_device_new_from_syspath(void *udev, const char *syspath) { (void)udev; (void)syspath; return NULL; }
void *udev_device_unref(void *device) { (void)device; return NULL; }
void *udev_device_ref(void *device) { (void)device; return NULL; }
void *udev_device_get_parent(void *device) { (void)device; return NULL; }
void *udev_device_get_udev(void *device) { (void)device; return NULL; }
const char *udev_device_get_syspath(void *device) { (void)device; return "real"; }
const char *udev_device_get_sysnum(void *device) { (void)device; return "real"; }
"""

PROGRAM = r"""
#define _GNU_SOURCE
#include <dlfcn.h>
#include <stdio.h>
#include <string.h>
#include <unistd.h>

#define PAD "/sys/devices/virtual/input/droiddeck0"

int next_marker(void);

static void *lib;

static void *sym(const char *name) { return dlsym(lib, name); }

int main(int argc, char **argv) {
  char link[4096];
  int udev_a, udev_b, i;
  void *device, *parent, *other, *first, *second, *entry;

  if (strcmp(argv[1], "once") == 0) {
    printf("readlink %d\n", readlink("/proc/self/exe", link, sizeof(link)) > 0);
    return 0;
  }
  if (strcmp(argv[1], "next") == 0) {
    int (*chained)(void) = (int (*)(void))dlsym(RTLD_DEFAULT, "chained");
    printf("next %d\n", next_marker());
    if (chained != NULL) printf("chained %d\n", chained());
    return 0;
  }
  lib = dlopen(argv[2], RTLD_NOW | RTLD_LOCAL);
  void *(*new_device)(void *, const char *) = sym("udev_device_new_from_syspath");
  void *(*ref)(void *) = sym("udev_device_ref");
  void *(*unref)(void *) = sym("udev_device_unref");
  void *(*get_parent)(void *) = sym("udev_device_get_parent");
  void *(*get_udev)(void *) = sym("udev_device_get_udev");
  const char *(*syspath)(void *) = sym("udev_device_get_syspath");
  const char *(*sysnum)(void *) = sym("udev_device_get_sysnum");
  void *(*enumerate_new)(void *) = sym("udev_enumerate_new");
  int (*match)(void *, const char *) = sym("udev_enumerate_add_match_subsystem");
  int (*scan)(void *) = sym("udev_enumerate_scan_devices");
  void *(*list)(void *) = sym("udev_enumerate_get_list_entry");
  void *(*next)(void *) = sym("udev_list_entry_get_next");
  void *(*by_name)(void *, const char *) = sym("udev_list_entry_get_by_name");
  const char *(*name)(void *) = sym("udev_list_entry_get_name");

  printf("missing %d\n", sym("udev_no_such_symbol") == NULL);
  printf("plain %d\n", dlsym(RTLD_DEFAULT, "printf") != NULL);
  device = new_device(&udev_a, PAD);
  printf("syspath %s sysnum %s\n", syspath(device), sysnum(device));
  parent = get_parent(device);
  printf("parent %s stable %d\n", syspath(parent), parent == get_parent(device));
  ref(device);
  unref(device);
  printf("held %s\n", syspath(device));
  ref(parent);
  unref(device);
  printf("released %s parent %s\n", syspath(device), syspath(parent));
  unref(parent);
  printf("parent released %s\n", syspath(parent));
  for (i = 0; i < 1000; i++) {
    device = new_device(&udev_a, PAD);
    get_parent(device);
    get_parent(device);
    unref(device);
  }
  device = new_device(&udev_a, PAD);
  other = new_device(&udev_b, PAD);
  printf("contexts %d %d\n", get_udev(device) == (void *)&udev_a, get_udev(other) == (void *)&udev_b);
  first = enumerate_new(&udev_a);
  second = enumerate_new(&udev_b);
  match(first, "input");
  match(second, "input");
  scan(first);
  scan(second);
  entry = list(first);
  list(second);
  printf("entry %s tail %s\n", name(entry), name(next(entry)));
  printf("second tail %s\n", name(next(list(second))));
  printf("by name %s\n", name(by_name(next(entry), PAD)) == NULL ? "none" : name(by_name(entry, PAD)));
  fflush(stdout);
  return 0;
}
"""


@unittest.skipUnless(sys.platform == "linux" and shutil.which("cc"), "needs Linux and cc")
class InputUdevPreloadTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.dir = Path(tempfile.mkdtemp())
        build = cls.dir / "build"
        build.mkdir()
        sources = (("once.c", ONCE), ("first.c", FIRST), ("second.c", SECOND), ("chain.c", CHAIN), ("udev.c", UDEV),
                   ("program.c", PROGRAM))
        for name, text in sources:
            (build / name).write_text(text)
        cls.lib = cls.dir / "libddsession.so"
        subprocess.run(["cc", "-shared", "-fPIC", "-O2", "-Wall", "-pthread", "-o", str(cls.lib),
                        *map(str, sorted(PRELOAD.glob("*.c"))), "-ldl"], check=True)
        for name in ("once", "first", "second", "chain"):
            subprocess.run(["cc", "-shared", "-fPIC", "-O0", "-pthread", "-o", str(cls.dir / f"lib{name}.so"),
                            str(build / f"{name}.c"), "-ldl"], check=True)
        cls.udev = cls.dir / "libudev.so.1"
        subprocess.run(["cc", "-shared", "-fPIC", "-O2", "-o", str(cls.udev), str(build / "udev.c")], check=True)
        cls.program = cls.dir / "program"
        subprocess.run(["cc", "-O0", "-o", str(cls.program), str(build / "program.c"),
                        str(cls.dir / "libfirst.so"), "-ldl"], check=True)
        cls.pads = cls.dir / "pads"
        cls.pads.mkdir()
        (cls.pads / "event0").write_text("")

    @classmethod
    def tearDownClass(cls):
        shutil.rmtree(cls.dir, ignore_errors=True)

    def run_program(self, mode, *preloads):
        env = dict(os.environ, LD_PRELOAD=":".join(str(self.dir / name) for name in ("libddsession.so", *preloads)),
                   FAKE_EVDEV_MEMFD_PATHS="0=/pad", FAKE_EVDEV_DIR=str(self.pads))
        env.pop("FAKE_EVDEV_UINPUT", None)
        result = subprocess.run([str(self.program), mode, str(self.udev)], env=env, capture_output=True, text=True,
                                timeout=20)
        self.assertEqual(result.returncode, 0, result.stderr)
        return result.stdout.splitlines()

    def test_a_lookup_from_another_preloads_initialiser_does_not_reenter_it(self):
        self.assertEqual(self.run_program("once", "libonce.so"), ["readlink 1"])

    def test_the_real_lookup_still_sees_the_original_caller(self):
        self.assertEqual(self.run_program("next", "libfirst.so", "libsecond.so"), ["next 2"])

    def test_another_preloads_dlsym_stays_in_the_chain(self):
        self.assertEqual(self.run_program("next", "libfirst.so", "libchain.so", "libsecond.so"), ["next 2", "chained 1"])

    def test_a_program_resolving_udev_through_dlsym_gets_the_pads(self):
        pad = "/sys/devices/virtual/input/droiddeck0"
        self.assertEqual(self.run_program("udev"), [
            "missing 1",
            "plain 1",
            f"syspath {pad} sysnum 0",
            "parent /sys/devices/droiddeck0/input0 stable 1",
            f"held {pad}",
            "released real parent /sys/devices/droiddeck0/input0",
            "parent released real",
            "contexts 1 1",
            f"entry {pad} tail real1",
            "second tail real2",
            f"by name {pad}",
        ])


if __name__ == "__main__":
    unittest.main()
