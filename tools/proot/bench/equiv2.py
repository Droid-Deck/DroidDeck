"""Fast path equivalence, part two: symlinked directories (Wine's dosdevices), "..", trailing
slashes, realpath/getcwd, the write family, Unix sockets, xattrs, statfs, inotify and fstat.

Run under proot with and without the fast path (run-device.sh fpoff / fastpath) and diff the
output: every line must match. Builds its own tree under /root/fx and /mnt/droiddeck-sd/fx.
"""
import ctypes
import errno
import os
import shutil
import socket
import stat
import sys

libc = ctypes.CDLL(None, use_errno=True)
libc.realpath.restype = ctypes.c_char_p
libc.realpath.argtypes = [ctypes.c_char_p, ctypes.c_char_p]
libc.getcwd.restype = ctypes.c_char_p
libc.getcwd.argtypes = [ctypes.c_char_p, ctypes.c_size_t]
libc.get_nprocs.restype = ctypes.c_int

FX = "/root/fx"
SD = "/mnt/droiddeck-sd/fx"


def setup():
    for d in (FX, SD):
        shutil.rmtree(d, ignore_errors=True)
    os.makedirs(FX + "/pfx/drive_c/windows/system32")
    os.makedirs(FX + "/pfx/dosdevices")
    os.makedirs(FX + "/real/sub/deeper")
    os.makedirs(SD + "/data/inner")
    with open(FX + "/pfx/drive_c/windows/system32/kernel32.dll", "w") as f:
        f.write("MZ" * 10)
    with open(FX + "/real/sub/file.txt", "w") as f:
        f.write("hello")
    with open(SD + "/data/inner/asset.pak", "w") as f:
        f.write("pak" * 7)
    os.symlink("../drive_c", FX + "/pfx/dosdevices/c:")
    os.symlink("/", FX + "/pfx/dosdevices/z:")
    os.symlink(SD + "/data", FX + "/pfx/dosdevices/d:")
    os.symlink("real/sub", FX + "/rel-dir")
    os.symlink(FX + "/real", FX + "/abs-dir")
    os.symlink("rel-dir", FX + "/chain1")
    os.symlink("chain1", FX + "/chain2")
    os.symlink("nowhere", FX + "/dangling")
    os.symlink("loop-b", FX + "/loop-a")
    os.symlink("loop-a", FX + "/loop-b")
    os.symlink("rel-dir/file.txt", FX + "/file-link")
    os.symlink("../fx/real/sub/../sub/deeper", FX + "/dotdot-link")
    os.symlink("/etc", FX + "/etc-link")
    os.symlink("/proc/self", FX + "/proc-link")
    os.symlink("/dev/null", FX + "/null-link")
    os.symlink("../../../root/fx/real", SD + "/back-to-root")


PATHS = [
    "pfx/dosdevices/c:/windows/system32/kernel32.dll",
    "pfx/dosdevices/c:/windows/system32/KERNEL32.DLL",
    "pfx/dosdevices/c:/windows/system32",
    "pfx/dosdevices/c:/windows/system32/",
    "pfx/dosdevices/c:/windows/system32/kernel32.dll/",
    "pfx/dosdevices/z:/root/fx/real/sub/file.txt",
    "pfx/dosdevices/z:/etc/os-release",
    "pfx/dosdevices/z:/mnt/droiddeck-sd/fx/data/inner/asset.pak",
    "pfx/dosdevices/z:/proc/self/status",
    "pfx/dosdevices/z:/nope/x",
    "pfx/dosdevices/d:/inner/asset.pak",
    "pfx/dosdevices/d:/inner/missing.pak",
    "pfx/dosdevices/d:/../back-to-root/sub/file.txt",
    "pfx/dosdevices/c:/../dosdevices/c:/windows",
    "rel-dir/file.txt",
    "rel-dir/../sub/file.txt",
    "rel-dir/..",
    "abs-dir/sub/deeper",
    "chain2/file.txt",
    "chain2",
    "chain2/",
    "dangling",
    "dangling/x",
    "loop-a",
    "loop-a/x",
    "file-link",
    "file-link/",
    "dotdot-link",
    "etc-link/os-release",
    "proc-link/status",
    "null-link",
    "real/sub/./file.txt",
    "real//sub///file.txt",
    "real/sub/file.txt/../file.txt",
    "nope",
    "nope/deeper",
    ".",
    "..",
    "../fx/real",
]


def call(fn):
    try:
        return repr(fn())
    except OSError as e:
        return "E" + errno.errorcode.get(e.errno, str(e.errno))


def realpath(p):
    ctypes.set_errno(0)
    r = libc.realpath(p.encode(), None)
    if r is None:
        return "E" + errno.errorcode.get(ctypes.get_errno(), "?")
    return r.decode()


def mode_of(s):
    return (stat.S_IFMT(s.st_mode), s.st_size if stat.S_ISREG(s.st_mode) else 0)


def probe(p):
    out = [
        "stat=" + call(lambda: mode_of(os.stat(p))),
        "lstat=" + call(lambda: mode_of(os.lstat(p))),
        "readlink=" + call(lambda: os.readlink(p)),
        "read=" + call(lambda: open(p, "rb").read(8)),
        "list=" + call(lambda: sorted(os.listdir(p))[:5]),
        "access=" + call(lambda: (os.access(p, os.R_OK), os.access(p, os.W_OK, effective_ids=True))),
        "realpath=" + realpath(p),
        "nofollow=" + call(lambda: os.close(os.open(p, os.O_RDONLY | os.O_NOFOLLOW))),
        "statvfs=" + call(lambda: (lambda v: (v.f_bsize, v.f_frsize, v.f_namemax, v.f_flag, v.f_fsid, v.f_files > 0))(os.statvfs(p))),
        "xattr=" + call(lambda: os.getxattr(p, "user.DOSATTRIB")),
        "lxattr=" + call(lambda: len(os.listxattr(p, follow_symlinks=False)) >= 0),
    ]
    return " ".join(out)


def writes(base):
    out = []
    w = base + "/w"
    t = lambda label, fn: out.append(f"  {label}: {call(fn)}")
    t("mkdir", lambda: os.mkdir(w))
    t("mkdir again", lambda: os.mkdir(w))
    t("mkdir under file", lambda: os.mkdir(base + "/real/sub/file.txt/x"))
    t("mkdir missing parent", lambda: os.mkdir(w + "/a/b"))
    t("create", lambda: os.close(os.open(w + "/f", os.O_CREAT | os.O_WRONLY, 0o640)))
    t("excl existing", lambda: os.open(w + "/f", os.O_CREAT | os.O_EXCL | os.O_WRONLY))
    t("symlink", lambda: os.symlink("f", w + "/lf"))
    t("symlink exists", lambda: os.symlink("f", w + "/lf"))
    t("create through link", lambda: os.close(os.open(w + "/lf", os.O_CREAT | os.O_WRONLY)))
    t("dangling create", lambda: (os.symlink("made-by-link", w + "/dl"), os.close(os.open(w + "/dl", os.O_CREAT | os.O_WRONLY)), os.path.lexists(w + "/made-by-link"))[2])
    t("excl through link", lambda: os.open(w + "/lf", os.O_CREAT | os.O_EXCL | os.O_WRONLY))
    t("chmod", lambda: (os.chmod(w + "/lf", 0o600), oct(os.stat(w + "/f").st_mode & 0o777))[1])
    t("utime", lambda: (os.utime(w + "/f", (1000, 2000)), os.stat(w + "/f").st_mtime)[1])
    t("utime link nofollow", lambda: os.utime(w + "/lf", (3, 4), follow_symlinks=False))
    t("futimens", lambda: (lambda fd: (os.utime(fd, (5, 6)), os.close(fd)))(os.open(w + "/f", os.O_RDONLY)))
    t("truncate", lambda: (os.truncate(w + "/lf", 7), os.stat(w + "/f").st_size)[1])
    t("rename file", lambda: os.rename(w + "/f", w + "/g"))
    t("rename missing", lambda: os.rename(w + "/f", w + "/h"))
    t("rename link", lambda: os.rename(w + "/lf", w + "/lg"))
    t("readlink renamed", lambda: os.readlink(w + "/lg"))
    t("replace", lambda: (open(w + "/tmp", "w").write("new"), os.replace(w + "/tmp", w + "/g"), open(w + "/g").read())[2])
    t("rename dir", lambda: (os.mkdir(w + "/d1"), os.rename(w + "/d1", w + "/d2"))[1])
    t("rename trailing", lambda: os.rename(w + "/d2/", w + "/d3"))
    t("unlink", lambda: os.unlink(w + "/g"))
    t("unlink missing", lambda: os.unlink(w + "/g"))
    t("unlink dir", lambda: os.unlink(w + "/d3"))
    t("unlink trailing", lambda: os.unlink(w + "/lg/"))
    t("rmdir nonempty", lambda: os.rmdir(w))
    t("rmdir file", lambda: os.rmdir(w + "/lg"))
    t("unlink link", lambda: os.unlink(w + "/lg"))
    t("rmdir", lambda: os.rmdir(w + "/d3"))
    t("listing", lambda: sorted(os.listdir(w)))
    for name in os.listdir(w):
        call(lambda: os.unlink(w + "/" + name))
    t("rmdir done", lambda: os.rmdir(w))
    return out


def sockets(base):
    out = []
    path = base + "/s.sock"
    try:
        os.unlink(path)
    except OSError:
        pass
    srv = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
    out.append("  bind " + call(lambda: srv.bind(path)))
    if not os.path.exists(path):
        # adb's shell may not create sockets under /data/local/tmp; the app can.
        srv.close()
        return out
    out.append("  name " + call(lambda: srv.getsockname()))
    out.append("  listen " + call(lambda: srv.listen(1)))
    out.append("  is socket " + call(lambda: stat.S_ISSOCK(os.stat(path).st_mode)))
    cli = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
    out.append("  connect " + call(lambda: cli.connect(path)))
    conn, _ = srv.accept()
    out.append("  peer " + call(lambda: cli.getpeername()))
    cli.sendall(b"ping")
    out.append("  recv " + call(lambda: conn.recv(4)))
    srv.setblocking(False)
    out.append("  accept none " + call(lambda: srv.accept()))
    for s in (conn, cli, srv):
        s.close()
    out.append("  connect missing " + call(lambda: socket.socket(socket.AF_UNIX).connect(base + "/nope.sock")))
    a = socket.socket(socket.AF_UNIX)
    out.append("  abstract " + call(lambda: (a.bind("\0fp-equiv"), a.getsockname())[1]))
    a.close()
    os.unlink(path)
    return out


def main():
    setup()
    for cwd in [FX, "/", FX + "/pfx/dosdevices/c:/windows", FX + "/chain2", SD + "/data", "/mnt/droiddeck-sd"]:
        print("== cwd", cwd, call(lambda: os.chdir(cwd)), "getcwd", call(os.getcwd), "libc", libc.getcwd(None, 0))
        for p in PATHS:
            print(" ", p, probe(p))
        d = os.open(".", os.O_RDONLY)
        for p in PATHS[:12]:
            print("   at", p, call(lambda: mode_of(os.stat(p, dir_fd=d))), call(lambda: mode_of(os.stat(p, dir_fd=d, follow_symlinks=False))))
        print("   fstat", call(lambda: stat.S_IFMT(os.fstat(d).st_mode)))
        os.close(d)
    for base in (FX, SD, FX + "/pfx/dosdevices/d:", FX + "/chain2"):
        os.chdir("/")
        print("== writes in", base)
        print("\n".join(writes(base)))
        print("== sockets in", base)
        print("\n".join(sockets(base)))
    os.chdir(FX)
    print("== relative writes")
    print("\n".join(writes(".")))
    print("== moved cwd")
    os.mkdir(FX + "/mv1")
    os.chdir(FX + "/mv1")
    os.rename(FX + "/mv1", FX + "/mv2")
    print("  getcwd", call(os.getcwd), libc.getcwd(None, 0))
    print("  rel stat", call(lambda: mode_of(os.stat("."))))
    os.chdir(FX)
    os.rmdir(FX + "/mv2")
    print("== deleted cwd")
    os.mkdir(FX + "/gone")
    os.chdir(FX + "/gone")
    os.rmdir(FX + "/gone")
    print("  getcwd", call(os.getcwd))
    os.chdir(FX)
    print("== inotify")
    lib_in = libc.inotify_init1(0)
    for p in (FX + "/chain2", FX + "/pfx/dosdevices/c:/windows", FX + "/dangling", FX + "/nope"):
        ctypes.set_errno(0)
        w = libc.inotify_add_watch(lib_in, p.encode(), 0x100 | 0x200)
        print(" ", p, "ok" if w >= 0 else "E" + errno.errorcode.get(ctypes.get_errno(), "?"))
    os.close(lib_in)
    print("== cpus", libc.get_nprocs() == os.cpu_count(), os.cpu_count() > 0)
    for d in (FX, SD):
        shutil.rmtree(d, ignore_errors=True)


if __name__ == "__main__":
    main()
