import errno
import os
import sys
import tempfile
import time


def report(label, check):
    try:
        print(label, check())
    except OSError as error:
        print(label, "ERR", errno.errorcode.get(error.errno, error.errno))


def enter(path):
    fd = os.open(path, os.O_RDONLY | os.O_DIRECTORY)
    try:
        os.fchdir(fd)
        return os.getcwd(), os.readlink("/proc/self/cwd"), os.path.samefile(".", path)
    finally:
        os.close(fd)


def enter_file(base):
    fd = os.open(base + "/real/file", os.O_RDONLY)
    try:
        os.fchdir(fd)
    finally:
        os.close(fd)


def enter_deleted(base):
    os.mkdir(base + "/gone")
    fd = os.open(base + "/gone", os.O_RDONLY)
    os.rmdir(base + "/gone")
    try:
        os.fchdir(fd)
        return "ok"
    finally:
        os.close(fd)
        os.chdir("/")


def enter_renamed(base):
    os.mkdir(base + "/before")
    fd = os.open(base + "/before", os.O_RDONLY)
    os.rename(base + "/before", base + "/after")
    try:
        os.fchdir(fd)
        return os.getcwd() == base + "/after"
    finally:
        os.close(fd)
        os.chdir("/")
        os.rmdir(base + "/after")


def relative(base):
    fd = os.open(base, os.O_RDONLY)
    os.fchdir(fd)
    os.close(fd)
    seen = os.path.exists("real/file"), os.path.exists("link/file")
    os.chdir("real")
    inside = os.getcwd() == base + "/real"
    os.chdir("..")
    return seen + (inside, os.getcwd() == base)


def cost(path, rounds=300):
    fd = os.open(path, os.O_RDONLY)
    back = os.open("/", os.O_RDONLY)
    start = time.perf_counter()
    for _ in range(rounds):
        os.fchdir(fd)
        os.fchdir(back)
    elapsed = time.perf_counter() - start
    os.close(fd)
    os.close(back)
    return round(elapsed / rounds / 2 * 1e6, 1)


def main():
    extra = sys.argv[1:]
    base = tempfile.mkdtemp(prefix="fchdir-")
    os.mkdir(base + "/real")
    os.symlink(base + "/real", base + "/link")
    os.mkdir(base + "/dot.")
    open(base + "/real/file", "w").close()
    for path in ["/", "/tmp", "/usr/lib", "/proc/self", "/dev", base + "/real", base + "/link", base + "/dot."] + extra:
        report(path, lambda: enter(path))
    report("file", lambda: enter_file(base))
    report("deleted", lambda: enter_deleted(base))
    report("renamed", lambda: enter_renamed(base))
    report("badfd", lambda: os.fchdir(987))
    report("relative", lambda: relative(base))
    os.chdir("/")
    for path in ["/usr/lib"] + extra:
        print("fchdir_us", path, cost(path))
    os.unlink(base + "/real/file")
    os.unlink(base + "/link")
    os.rmdir(base + "/dot.")
    os.rmdir(base + "/real")
    os.rmdir(base)


main()
