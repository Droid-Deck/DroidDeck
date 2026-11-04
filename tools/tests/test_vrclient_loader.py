from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


@unittest.skipUnless(shutil.which("gcc"), "needs gcc on a Linux host")
class VrclientLoaderTest(unittest.TestCase):
    def test_vrclient_keeps_syscall_interposition_without_changing_other_dsos(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            module = root / "module.c"
            module.write_text(r"""
#include <errno.h>
#include <stddef.h>
#include <unistd.h>
#include <sys/syscall.h>
long syscall(long number, ...) { (void)number; errno = ENOSYS; return -1; }
int probe(void) {
    void *head = NULL; size_t length = 0;
    return syscall(SYS_get_robust_list, 0L, &head, &length) == 0 && head && length > 0;
}
""")
            subprocess.run(["gcc", "-shared", "-fPIC", "-O2", str(module), "-o",
                            str(root / "vrclient.so")], check=True)
            shutil.copy(root / "vrclient.so", root / "ordinary.so")
            robust = Path(__file__).resolve().parents[1] / "linuxfs/preload/robust.c"
            harness = root / "loader.c"
            harness.write_text(r"""
#define _GNU_SOURCE
#include <assert.h>
#include <dlfcn.h>
int bl_fsync_syscall(long n, const long *a, long *r) { (void)n; (void)a; (void)r; return 0; }
int bl_writewatch_syscall(long n, const long *a, long (*real)(long, ...), long *r) { (void)n; (void)a; (void)real; (void)r; return 0; }
int bl_clock_syscall(long n, const long *a, long (*real)(long, ...), long *r) { (void)n; (void)a; (void)real; (void)r; return 0; }
#include """ + '"' + robust.as_posix() + '"' + r"""
int main(int argc, char **argv) {
    assert(argc == 3);
    void *ordinary = dlopen(argv[1], RTLD_NOW | RTLD_DEEPBIND);
    assert(ordinary);
    int (*probe)(void) = dlsym(ordinary, "probe");
    assert(probe && probe() == 0);
    dlclose(ordinary);
    void *vr = dlopen(argv[2], RTLD_NOW | RTLD_DEEPBIND);
    assert(vr);
    probe = dlsym(vr, "probe");
    assert(probe && probe() == 1);
    dlclose(vr);
}
""")
            subprocess.run(["gcc", "-O2", "-rdynamic", "-pthread", str(harness),
                            "-ldl", "-o", str(root / "loader")], check=True)
            subprocess.run([str(root / "loader"), str(root / "ordinary.so"),
                            str(root / "vrclient.so")], check=True, timeout=10)


if __name__ == "__main__":
    unittest.main()
