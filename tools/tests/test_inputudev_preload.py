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

MARK = r"""
#include <stdlib.h>
#include <string.h>

char *realpath(const char *path, char *resolved) {
  (void)path;
  if (resolved == NULL) return strdup("marked");
  strcpy(resolved, "marked");
  return resolved;
}
"""

GLIBC = r"""
#define _GNU_SOURCE
#include <dlfcn.h>
#include <gnu/libc-version.h>
#include <stddef.h>

int main(void) { return dlvsym(RTLD_NEXT, "dlsym", "GLIBC_2.2.5") == NULL && gnu_get_libc_version() == NULL; }
"""

UDEV = r"""
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

struct list { const char *name; };
struct enumerate { struct list head; struct list added; char name[16]; int fail; int use_added; };

void *udev_enumerate_new(void *udev) {
  static int count;
  struct enumerate *enumerate = calloc(1, sizeof(*enumerate));
  (void)udev;
  snprintf(enumerate->name, sizeof(enumerate->name), "real%d", count++);
  enumerate->head.name = enumerate->name;
  enumerate->added.name = "added";
  return enumerate;
}
void *udev_enumerate_unref(void *enumerate) { free(enumerate); return NULL; }
int udev_enumerate_add_match_subsystem(void *enumerate, const char *subsystem) {
  if (strcmp(subsystem, "fail") == 0) ((struct enumerate *)enumerate)->fail = 1;
  return 0;
}
int udev_enumerate_add_match_parent(void *enumerate, void *parent) {
  (void)parent;
  ((struct enumerate *)enumerate)->head.name = "parented";
  return 1;
}
int udev_enumerate_add_syspath(void *enumerate, const char *syspath) {
  (void)syspath;
  ((struct enumerate *)enumerate)->use_added = 1;
  return 0;
}
int udev_enumerate_scan_devices(void *enumerate) { return ((struct enumerate *)enumerate)->fail ? -1 : 0; }
void *udev_enumerate_get_list_entry(void *enumerate) {
  struct enumerate *e = enumerate;
  return e->use_added ? &e->added : &e->head;
}
void *udev_list_entry_get_next(void *entry) { (void)entry; return NULL; }
void *udev_list_entry_get_by_name(void *entry, const char *name) {
  return strcmp(((struct list *)entry)->name, name) == 0 ? entry : NULL;
}
const char *udev_list_entry_get_name(void *entry) { return ((struct list *)entry)->name; }
void *udev_device_new_from_syspath(void *udev, const char *syspath) { (void)udev; (void)syspath; return NULL; }
void *udev_device_new_from_subsystem_sysname(void *udev, const char *subsystem, const char *sysname) {
  (void)udev; (void)subsystem; (void)sysname;
  return NULL;
}
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
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#define PAD "/sys/devices/virtual/input/droiddeck0"
#define PAD1 "/sys/devices/virtual/input/droiddeck1"
#define HANDLES 256

int next_marker(void);

static void *lib;
static void *(*new_device)(void *, const char *);
static void *(*new_sysname)(void *, const char *, const char *);
static void *(*ref)(void *);
static void *(*unref)(void *);
static void *(*get_parent)(void *);
static void *(*get_udev)(void *);
static const char *(*syspath)(void *);
static const char *(*sysname)(void *);
static const char *(*sysnum)(void *);
static const char *(*devnode)(void *);
static const char *(*property)(void *, const char *);
static void *(*properties)(void *);
static void *(*enumerate_new)(void *);
static int (*match)(void *, const char *);
static int (*match_parent)(void *, void *);
static int (*add_syspath)(void *, const char *);
static int (*scan)(void *);
static void *(*list)(void *);
static void *(*next)(void *);
static void *(*by_name)(void *, const char *);
static const char *(*name)(void *);
static const char *(*value)(void *);

static void *sym(const char *symbol) { return dlsym(lib, symbol); }

static const char *entry_name(void *entry) { return entry != NULL ? name(entry) : "none"; }

static void print_list(const char *label, void *entry) {
  printf("%s", label);
  for (; entry != NULL; entry = next(entry)) printf(" %s", name(entry));
  printf("\n");
}

static void print_properties(const char *label, void *device) {
  void *entry;

  printf("%s", label);
  for (entry = properties(device); entry != NULL; entry = next(entry)) printf(" %s=%s", name(entry), value(entry));
  printf("\n");
}

static void *matched(void *udev, const char *subsystem) {
  void *enumerate = enumerate_new(udev);

  match(enumerate, subsystem);
  return enumerate;
}

static void pad_file(const char *node, int present) {
  char path[4096];
  FILE *file;

  snprintf(path, sizeof(path), "%s/%s", getenv("FAKE_EVDEV_DIR"), node);
  if (!present) {
    unlink(path);
  } else if ((file = fopen(path, "w")) != NULL) {
    fclose(file);
  }
}

static void run_udev(void) {
  int udev_a, udev_b, i, failures = 0;
  void *device, *parent, *other, *entry, *held[HANDLES] = {0};
  char resolved[4096];
  const char *path;

  printf("missing %d\n", sym("udev_no_such_symbol") == NULL);
  printf("plain %d\n", dlsym(RTLD_DEFAULT, "printf") != NULL);
  device = new_device(&udev_a, PAD);
  printf("syspath %s sysnum %s devnode %s\n", syspath(device), sysnum(device), devnode(device));
  parent = get_parent(device);
  printf("parent %s sysname %s stable %d\n", syspath(parent), sysname(parent), parent == get_parent(device));
  print_properties("device properties", device);
  print_properties("parent properties", parent);
  entry = properties(device);
  printf("joystick %s unknown %d\n", value(by_name(entry, "ID_INPUT_JOYSTICK")), by_name(entry, "NO_SUCH_KEY") == NULL);
  ref(device);
  unref(device);
  printf("held %s\n", syspath(device));
  ref(parent);
  unref(device);
  printf("retained %s\n", syspath(parent));
  unref(parent);
  for (i = 0; i < 1000; i++) {
    device = new_device(&udev_a, PAD);
    if (device == NULL || get_parent(device) == NULL || get_parent(device) != get_parent(device) ||
        strcmp(syspath(device), PAD) != 0)
      failures++;
    if (device != NULL) unref(device);
  }
  printf("reuse failures %d\n", failures);
  for (i = 0; i < HANDLES && (held[i] = new_device(&udev_a, PAD)) != NULL; i++) {
  }
  printf("full %d extra %d parent %d\n", i, new_device(&udev_a, PAD) == NULL, get_parent(held[0]) == NULL);
  unref(held[1]);
  parent = get_parent(held[0]);
  printf("freed parent %s stable %d\n", parent != NULL ? syspath(parent) : "none", parent == get_parent(held[0]));
  for (i = 0; i < HANDLES; i++)
    if (i != 1 && held[i] != NULL) unref(held[i]);
  path = realpath("/sys/class/input/droiddeck-input0", resolved);
  printf("parent link %s\n", path != NULL ? path : "none");
  path = realpath("/sys/class/input/input0", resolved);
  printf("kernel link shadowed %d\n", path != NULL && strncmp(path, "/sys/devices/droiddeck", 22) == 0);
  printf("kernel sysname shadowed %d\n", new_sysname(&udev_a, "input", "input0") != NULL);
  other = new_sysname(&udev_a, "input", "droiddeck-input0");
  printf("parent sysname %s\n", other != NULL ? syspath(other) : "none");
  if (other != NULL) unref(other);
  device = new_device(&udev_a, PAD);
  other = new_device(&udev_b, PAD);
  printf("contexts %d %d\n", get_udev(device) == (void *)&udev_a, get_udev(other) == (void *)&udev_b);
}

static void run_stable(void) {
  int udev;
  char copy[64];
  void *device = new_device(&udev, PAD);
  const char *before = devnode(device);

  snprintf(copy, sizeof(copy), "%s", before);
  pad_file("event0", 0);
  printf("gone %d\n", new_device(&udev, PAD) == NULL);
  printf("devnode %s same %d property %s\n", devnode(device), devnode(device) == before && strcmp(before, copy) == 0,
         property(device, "DEVNAME"));
}

static void run_enumerate(void) {
  int udev, i;
  void *first, *entry, *found, *missing, *failed, *parented, *real_parent, *device, *parent;

  first = matched(&udev, "input");
  scan(first);
  entry = list(first);
  print_list("scan", entry);
  pad_file("event1", 1);
  print_list("same scan", list(first));
  entry = list(first);
  found = by_name(entry, PAD);
  missing = by_name(entry, PAD1);
  printf("by name %s missing %d\n", entry_name(found), missing == NULL);
  for (i = 0; i < 20; i++) {
    void *other = matched(&udev, "input");
    scan(other);
    list(other);
  }
  printf("kept tail %s\n", entry_name(next(entry)));
  scan(first);
  print_list("rescan", list(first));
  printf("rescan by name %s\n", entry_name(by_name(list(first), PAD1)));
  add_syspath(first, "/sys/devices/added");
  print_list("added", list(first));
  failed = matched(&udev, "input");
  match(failed, "fail");
  printf("failed scan %d\n", scan(failed));
  print_list("failed", list(failed));
  device = new_device(&udev, PAD);
  parent = get_parent(device);
  parented = enumerate_new(&udev);
  printf("synthetic parent %d\n", match_parent(parented, parent));
  scan(parented);
  print_list("parented", list(parented));
  real_parent = enumerate_new(&udev);
  printf("real parent %d\n", match_parent(real_parent, &udev));
  scan(real_parent);
  print_list("real parent list", list(real_parent));
}

int main(int argc, char **argv) {
  char link[4096];

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
  if (strcmp(argv[1], "realpath") == 0) {
    printf("realpath %s\n", realpath("/no/such/inputudev/path", link));
    return 0;
  }
  new_device = sym("udev_device_new_from_syspath");
  new_sysname = sym("udev_device_new_from_subsystem_sysname");
  ref = sym("udev_device_ref");
  unref = sym("udev_device_unref");
  get_parent = sym("udev_device_get_parent");
  get_udev = sym("udev_device_get_udev");
  syspath = sym("udev_device_get_syspath");
  sysname = sym("udev_device_get_sysname");
  sysnum = sym("udev_device_get_sysnum");
  devnode = sym("udev_device_get_devnode");
  property = sym("udev_device_get_property_value");
  properties = sym("udev_device_get_properties_list_entry");
  enumerate_new = sym("udev_enumerate_new");
  match = sym("udev_enumerate_add_match_subsystem");
  match_parent = sym("udev_enumerate_add_match_parent");
  add_syspath = sym("udev_enumerate_add_syspath");
  scan = sym("udev_enumerate_scan_devices");
  list = sym("udev_enumerate_get_list_entry");
  next = sym("udev_list_entry_get_next");
  by_name = sym("udev_list_entry_get_by_name");
  name = sym("udev_list_entry_get_name");
  value = sym("udev_list_entry_get_value");
  if (strcmp(argv[1], "stable") == 0) run_stable();
  else if (strcmp(argv[1], "enumerate") == 0) run_enumerate();
  else run_udev();
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
        (build / "glibc.c").write_text(GLIBC)
        probe = subprocess.run(["cc", "-o", str(build / "glibc"), str(build / "glibc.c"), "-ldl"], capture_output=True)
        if probe.returncode != 0:
            shutil.rmtree(cls.dir, ignore_errors=True)
            raise unittest.SkipTest("needs a glibc toolchain")
        sources = (("once.c", ONCE), ("first.c", FIRST), ("second.c", SECOND), ("chain.c", CHAIN), ("mark.c", MARK),
                   ("udev.c", UDEV), ("program.c", PROGRAM))
        for name, text in sources:
            (build / name).write_text(text)
        cls.lib = cls.dir / "libddsession.so"
        subprocess.run(["cc", "-shared", "-fPIC", "-O2", "-Wall", "-pthread", "-o", str(cls.lib),
                        *map(str, sorted(PRELOAD.glob("*.c"))), "-ldl"], check=True)
        for name in ("once", "first", "second", "chain", "mark"):
            subprocess.run(["cc", "-shared", "-fPIC", "-O0", "-pthread", "-o", str(cls.dir / f"lib{name}.so"),
                            str(build / f"{name}.c"), "-ldl"], check=True)
        cls.udev = cls.dir / "libudev.so.1"
        subprocess.run(["cc", "-shared", "-fPIC", "-O2", "-o", str(cls.udev), str(build / "udev.c")], check=True)
        cls.program = cls.dir / "program"
        subprocess.run(["cc", "-O0", "-o", str(cls.program), str(build / "program.c"),
                        str(cls.dir / "libfirst.so"), "-ldl"], check=True)
        cls.pads = cls.pad_dir("pads", "event0")

    @classmethod
    def tearDownClass(cls):
        shutil.rmtree(cls.dir, ignore_errors=True)

    @classmethod
    def pad_dir(cls, name, *nodes):
        pads = cls.dir / name
        pads.mkdir()
        for node in nodes:
            (pads / node).write_text("")
        return pads

    def run_program(self, mode, *preloads, pads=None, slots="0=/pad"):
        env = dict(os.environ, LD_PRELOAD=":".join(str(self.dir / name) for name in ("libddsession.so", *preloads)),
                   FAKE_EVDEV_MEMFD_PATHS=slots, FAKE_EVDEV_DIR=str(pads or self.pads))
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

    def test_non_udev_lookups_skip_the_opened_libudev(self):
        self.assertEqual(self.run_program("realpath", "libmark.so"), ["realpath marked"])

    def test_a_program_resolving_udev_through_dlsym_gets_the_pads(self):
        pad = "/sys/devices/virtual/input/droiddeck0"
        parent = "/sys/devices/droiddeck0/droiddeck-input0"
        self.assertEqual(self.run_program("udev"), [
            "missing 1",
            "plain 1",
            f"syspath {pad} sysnum 0 devnode /dev/input/event0",
            f"parent {parent} sysname droiddeck-input0 stable 1",
            f"device properties DEVNAME=/dev/input/event0 DEVPATH={pad[4:]} ID_INPUT=1 ID_INPUT_JOYSTICK=1",
            f"parent properties DEVPATH={parent[4:]} ID_INPUT=1 ID_INPUT_JOYSTICK=1",
            "joystick 1 unknown 1",
            f"held {pad}",
            f"retained {parent}",
            "reuse failures 0",
            "full 256 extra 1 parent 1",
            f"freed parent {parent} stable 1",
            f"parent link {parent}",
            "kernel link shadowed 0",
            "kernel sysname shadowed 0",
            f"parent sysname {parent}",
            "contexts 1 1",
        ])

    def test_a_handle_keeps_its_strings_after_the_pad_goes_away(self):
        self.assertEqual(self.run_program("stable", pads=self.pad_dir("stable", "event0")), [
            "gone 1",
            "devnode /dev/input/event0 same 1 property /dev/input/event0",
        ])

    def test_enumerations_keep_their_scan_and_their_state(self):
        pad = "/sys/devices/virtual/input/droiddeck0"
        pad1 = "/sys/devices/virtual/input/droiddeck1"
        self.assertEqual(self.run_program("enumerate", pads=self.pad_dir("enumerate", "event0"), slots="0=/pad;1=/pad"), [
            f"scan {pad} real0",
            f"same scan {pad} real0",
            f"by name {pad} missing 1",
            "kept tail real0",
            f"rescan {pad} {pad1} real0",
            f"rescan by name {pad1}",
            f"added {pad} {pad1} added",
            "failed scan 0",
            f"failed {pad} {pad1}",
            "synthetic parent 0",
            f"parented {pad}",
            "real parent 1",
            "real parent list parented",
        ])


if __name__ == "__main__":
    unittest.main()
