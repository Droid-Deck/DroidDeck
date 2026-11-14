import errno
import os
import shutil
import sys
import time

me = (os.getuid(), os.getgid())


def call(fn):
    try:
        return repr(fn())
    except OSError as e:
        return "E" + errno.errorcode.get(e.errno, str(e.errno))


def run(base, links):
    shutil.rmtree(base, ignore_errors=True)
    os.makedirs(base + "/d")
    open(base + "/f", "w").close()
    open(base + "/d/g", "w").close()
    if links:
        os.symlink("f", base + "/lf")
        os.symlink("nowhere", base + "/dl")
        os.symlink("d", base + "/ld")
        os.symlink("loop2", base + "/loop1")
        os.symlink("loop1", base + "/loop2")
    uid = lambda p, follow=True: os.stat(p, follow_symlinks=follow).st_uid
    cases = [
        ("path", lambda: os.chown(base + "/f", *me)),
        ("keep", lambda: os.chown(base + "/f", -1, -1)),
        ("uid only", lambda: os.chown(base + "/f", me[0], -1)),
        ("gid only", lambda: os.chown(base + "/f", -1, me[1])),
        ("foreign uid", lambda: os.chown(base + "/f", me[0] + 1, -1)),
        ("foreign gid", lambda: os.chown(base + "/f", -1, me[1] + 12345)),
        ("dir", lambda: os.chown(base + "/d", *me)),
        ("dir trailing slash", lambda: os.chown(base + "/d/", *me)),
        ("file trailing slash", lambda: os.chown(base + "/f/", *me)),
        ("missing", lambda: os.chown(base + "/nope", *me)),
        ("missing parent", lambda: os.chown(base + "/nope/x", *me)),
        ("under file", lambda: os.chown(base + "/f/x", *me)),
        ("empty", lambda: os.chown("", *me)),
        ("dotdot", lambda: os.chown(base + "/d/../f", *me)),
        ("fd", lambda: (lambda fd: (os.chown(fd, *me), os.close(fd))[1])(os.open(base + "/f", os.O_RDONLY))),
        ("dirfd rel", lambda: (lambda fd: (os.chown("g", *me, dir_fd=fd), os.close(fd))[1])(os.open(base + "/d", os.O_RDONLY))),
        ("dirfd abs", lambda: (lambda fd: (os.chown(base + "/f", *me, dir_fd=fd), os.close(fd))[1])(os.open(base + "/d", os.O_RDONLY))),
        ("bad dirfd", lambda: os.chown("g", *me, dir_fd=9999)),
        ("file dirfd", lambda: (lambda fd: (os.chown("g", *me, dir_fd=fd), os.close(fd))[1])(os.open(base + "/f", os.O_RDONLY))),
        ("cwd rel", lambda: (os.chdir(base + "/d"), os.chown("g", *me))[1]),
        ("cwd dotdot", lambda: (os.chdir(base + "/d"), os.chown("../f", *me))[1]),
    ]
    if links:
        cases += [
            ("link follow", lambda: os.chown(base + "/lf", *me)),
            ("link nofollow", lambda: os.chown(base + "/lf", *me, follow_symlinks=False)),
            ("dangling follow", lambda: os.chown(base + "/dl", *me)),
            ("dangling nofollow", lambda: os.chown(base + "/dl", *me, follow_symlinks=False)),
            ("dirlink", lambda: os.chown(base + "/ld/g", *me)),
            ("dirlink slash", lambda: os.chown(base + "/ld/", *me)),
            ("loop", lambda: os.chown(base + "/loop1", *me)),
            ("loop nofollow", lambda: os.chown(base + "/loop1", *me, follow_symlinks=False)),
            ("link foreign", lambda: os.chown(base + "/lf", me[0] + 1, -1)),
        ]
    out = []
    for label, fn in cases:
        os.chdir("/")
        out.append(f"  {label}: {call(fn)}")
    os.chdir("/")
    out.append("  owner after: " + call(lambda: (uid(base + "/f"), os.stat(base + "/f").st_gid)))
    shutil.rmtree(base, ignore_errors=True)
    return out


def bench(base, count):
    shutil.rmtree(base, ignore_errors=True)
    os.makedirs(base)
    names = [f"{base}/f{i}" for i in range(count)]
    for n in names:
        open(n, "w").close()
    start = time.perf_counter()
    for n in names:
        try:
            os.chown(n, *me)
        except OSError:
            pass
    elapsed = time.perf_counter() - start
    shutil.rmtree(base, ignore_errors=True)
    return elapsed


for base, links in (("/root/chown-eq", True), ("/tmp/chown-eq", True), ("/mnt/droiddeck-sd/chown-eq", False)):
    print("==", base)
    print("\n".join(run(base, links)))

if len(sys.argv) > 1:
    for base in ("/root/chown-bench", "/mnt/droiddeck-sd/chown-bench"):
        t = bench(base, int(sys.argv[1]))
        print(f"BENCH {base} {int(sys.argv[1])} calls {t:.3f}s {t / int(sys.argv[1]) * 1e6:.0f}us each", file=sys.stderr)
