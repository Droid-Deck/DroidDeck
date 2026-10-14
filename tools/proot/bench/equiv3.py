"""Fast path equivalence, part three: /proc (self, fd and exe links, readlink as proot answers it,
realpath through it, opening through fd links), link text naming host paths, creating in
directories whose host path is long, glue above a binding proot cannot look into, files the
caller may not stat, trailing slashes, shm_open, accept, passwd lookups and the _FORTIFY_SOURCE
entry points.

Run under proot with and without the fast path (run-device.sh fpoff / fastpath) and diff the
output: every line must match. The rig's rootfs is ROOT; run-device.sh binds /cache/fp/perf and
SHARED at /opt/fpx.
"""
import ctypes
import errno
import os
import shutil
import socket
import stat
import sys

ROOT = "/data/local/tmp/dd/mini"
SHARED = "/data/local/tmp/dd/fpx"
FX = "/root/fx3"
DEEP = FX + "/a-directory-name-long-enough/to-push-the-host-path/past-what-sun-path-holds/and-then-some"

libc = ctypes.CDLL(None, use_errno=True)
libc.realpath.restype = ctypes.c_char_p
libc.realpath.argtypes = [ctypes.c_char_p, ctypes.c_char_p]
libc.readlink.restype = ctypes.c_ssize_t
libc.readlink.argtypes = [ctypes.c_char_p, ctypes.c_char_p, ctypes.c_size_t]
libc.__readlink_chk.restype = ctypes.c_ssize_t
libc.__readlink_chk.argtypes = [ctypes.c_char_p, ctypes.c_char_p, ctypes.c_size_t, ctypes.c_size_t]
libc.__readlinkat_chk.restype = ctypes.c_ssize_t
libc.__readlinkat_chk.argtypes = [ctypes.c_int, ctypes.c_char_p, ctypes.c_char_p, ctypes.c_size_t, ctypes.c_size_t]
libc.__getcwd_chk.restype = ctypes.c_char_p
libc.__getcwd_chk.argtypes = [ctypes.c_char_p, ctypes.c_size_t, ctypes.c_size_t]
libc.shm_open.restype = ctypes.c_int
libc.shm_open.argtypes = [ctypes.c_char_p, ctypes.c_int, ctypes.c_uint]
libc.shm_unlink.restype = ctypes.c_int
libc.shm_unlink.argtypes = [ctypes.c_char_p]


def norm(s):
    return str(s).replace(str(os.getpid()), "PID").replace(str(ctypes.CDLL(None).gettid()), "TID")


def err():
    return "E" + errno.errorcode.get(ctypes.get_errno(), "?")


def call(fn):
    try:
        return norm(repr(fn()))
    except OSError as e:
        return "E" + errno.errorcode.get(e.errno, str(e.errno))


def realpath(p):
    ctypes.set_errno(0)
    r = libc.realpath(p.encode(), None)
    return err() if r is None else norm(r.decode())


def raw_readlink(p, size, chk=False):
    buf = ctypes.create_string_buffer(b"#" * (size + 2), size + 2)
    ctypes.set_errno(0)
    n = libc.__readlink_chk(p.encode(), buf, size, size + 2) if chk else libc.readlink(p.encode(), buf, size)
    if n < 0:
        return err()
    return norm((n, buf.raw[: min(n + 1, size)]))


def mode_of(s):
    return (stat.S_IFMT(s.st_mode), s.st_size if stat.S_ISREG(s.st_mode) else 0)


def opened(p):
    def go():
        fd = os.open(p, os.O_RDONLY)
        try:
            return mode_of(os.fstat(fd))
        finally:
            os.close(fd)
    return f"open={call(go)} stat={call(lambda: mode_of(os.stat(p)))}"


def links(p):
    sizes = [1, 5, 4096]
    try:
        whole = len(os.readlink(p))
        sizes += [whole - 1, whole, whole + 1, whole + 2]
    except OSError:
        pass
    out = [f"readlink={call(lambda: os.readlink(p))}", f"realpath={realpath(p)}",
           f"stat={call(lambda: mode_of(os.stat(p)))}", f"lstat={call(lambda: stat.S_IFMT(os.lstat(p).st_mode))}"]
    out += [f"buf{n}={raw_readlink(p, n)}" for n in sizes if n > 0]
    out.append(f"chk={raw_readlink(p, 64, True)}")
    return " ".join(out)


def proc_section():
    print("== /proc")
    os.makedirs(FX + "/dir", exist_ok=True)
    open(FX + "/file", "w").write("x")
    open(FX + "/doomed", "w").write("x")
    held = {
        "rootfs file": os.open(FX + "/file", os.O_RDONLY),
        "rootfs dir": os.open(FX + "/dir", os.O_RDONLY),
        "bound file": os.open("/proc/version", os.O_RDONLY),
        "sdcard": os.open("/sdcard", os.O_RDONLY),
        "shared bind": os.open("/mnt/droiddeck-sd", os.O_RDONLY),
        "dev null": os.open("/dev/null", os.O_RDONLY),
        "deleted": os.open(FX + "/doomed", os.O_RDONLY),
        "outside": os.open("/data/local/tmp", os.O_RDONLY),
    }
    os.unlink(FX + "/doomed")
    r, w = os.pipe()
    held["pipe"] = r
    s = socket.socket(socket.AF_UNIX, socket.SOCK_DGRAM)
    held["socket"] = s.fileno()
    for label, fd in held.items():
        for p in (f"/proc/self/fd/{fd}", f"/proc/{os.getpid()}/fd/{fd}", f"/proc/thread-self/fd/{fd}"):
            print(" ", label, norm(p), links(p))
        d = os.open("/proc/self/fd", os.O_RDONLY)
        print(" ", label, "at", call(lambda: os.readlink(str(fd), dir_fd=d)))
        os.close(d)
    for p in ("/proc/self/exe", f"/proc/{os.getpid()}/exe", "/proc/self/cwd", "/proc/self/root", "/proc/self",
              "/proc/thread-self", "/proc/self/task", "/proc/1/exe", "/proc/self/ns/mnt", "/proc/nope/exe",
              "/proc/self/fd/9999", "/proc/self/fd/", "/proc/self/fd/foo"):
        print(" ", norm(p), links(p))
    for p in ("/proc/self/cwd/file", "/proc/self/cwd/../fx3/dir", "/proc/self/exe/x", "/proc/self/fd/../status",
              "/proc/self/./status", "/proc/self/task/../status", "/proc/thread-self/status", "/proc/self/root/etc"):
        print(" ", norm(p), "realpath", realpath(p), "stat", call(lambda: mode_of(os.stat(p))))
    for p in ("/proc/self/status", "/proc/version", "/proc/loadavg", "/proc/self/cmdline", "/proc/meminfo"):
        print(" ", p, "first", call(lambda: open(p, "rb").read(5)), "access", call(lambda: os.access(p, os.R_OK)))
    print("  fd count", call(lambda: len(os.listdir("/proc/self/fd")) > 3))
    for label, fd in list(held.items()) + [("write end", w)]:
        for p in (f"/proc/self/fd/{fd}", f"/proc/{os.getpid()}/fd/{fd}", f"/proc/thread-self/fd/{fd}"):
            print(" ", label, "through", norm(p), opened(p), "access", call(lambda: os.access(p, os.R_OK)),
                  "nofollow", call(lambda: os.close(os.open(p, os.O_RDONLY | os.O_NOFOLLOW))),
                  "below", call(lambda: mode_of(os.stat(p + "/file"))), "slash", call(lambda: mode_of(os.stat(p + "/"))))
    for p in ("/proc/mounts", "/proc/net", "/proc/net/unix", "/proc/self/cwd/file", f"/proc/{os.getpid()}/cwd/dir",
              "/proc/self/root/etc/os-release", "/proc/self/exe", "/proc/1/fd/0", "/proc/1/fd/0/x", "/proc/1/cwd",
              "/proc/1/environ", "/proc/self/task"):
        print(" ", norm(p), opened(p), "access", call(lambda: os.access(p, os.F_OK)),
              "lstat", call(lambda: stat.S_IFMT(os.lstat(p).st_mode)))
    print("  mtab-like", call(lambda: (os.symlink("/proc/self/mounts", FX + "/mtab"), open(FX + "/mtab").read(4))[1]))
    for fd in list(held.values()) + [w]:
        try:
            os.close(fd)
        except OSError:
            pass
    s.detach()


def host_text_section():
    print("== link text naming host paths")
    cases = {
        "in-root": ROOT + "/etc/os-release",
        "root itself": ROOT,
        "bound sdcard": "/sdcard/Download",
        "shared bind": "/data/local/tmp/dd/tmp",
        "outside": "/data/local/tmp",
        "relative": "../fx3/file",
    }
    for label, text in cases.items():
        link = FX + "/ht-" + label.replace(" ", "-")
        os.symlink(text, link)
        print(" ", label, links(link), "read", call(lambda: open(link, "rb").read(4)))


def shared_section():
    print("== link text across bindings")
    os.makedirs(SHARED + "/sub", exist_ok=True)
    open(SHARED + "/target", "w").write("shared")
    os.makedirs("/data/local/tmp/dd/fpy", exist_ok=True)
    cases = [
        ("/opt/fpx/l-same", SHARED + "/target"),
        ("/opt/fpx/l-itself", SHARED),
        ("/opt/fpx/l-sub", SHARED + "/sub"),
        ("/opt/fpx/l-other-bind", "/data/local/tmp/dd/fpy"),
        ("/opt/fpx/l-rootfs", ROOT + "/etc/os-release"),
        ("/opt/fpx/l-rel", "target"),
        ("/opt/fpx/l-guest", "/opt/fpx/target"),
        ("/data/local/tmp/dd/fpy/l-into-shared", SHARED + "/target"),
        ("/data/local/tmp/dd/fpy/l-rootfs", ROOT + "/etc/os-release"),
        ("/data/local/tmp/dd/fpy/l-root", ROOT),
        (FX + "/l-shared", SHARED + "/target"),
        (FX + "/l-shared-guest", "/opt/fpx/target"),
    ]
    for link, text in cases:
        try:
            os.unlink(link)
        except OSError:
            pass
        os.symlink(text, link)
        print(" ", link, links(link), opened(link), "via", call(lambda: mode_of(os.stat(link + "/"))),
              "below", call(lambda: mode_of(os.stat(link + "/target"))))
    shutil.rmtree("/data/local/tmp/dd/fpy", ignore_errors=True)
    for name in os.listdir(SHARED):
        if name.startswith("l-"):
            os.unlink(SHARED + "/" + name)


def unseen_section():
    print("== files the caller may not stat")
    for p in ("/proc/1/fd/0", "/proc/1/fd/0/x", "/proc/1/fd", "/proc/1/environ", "/proc/1/mem", "/proc/1/fdinfo/0",
              "/proc/sys/vm/overcommit_memory", "/proc/1/task/1/fd/0"):
        print(" ", p, "stat", call(lambda: mode_of(os.stat(p))), "lstat", call(lambda: stat.S_IFMT(os.lstat(p).st_mode)),
              "access", call(lambda: os.access(p, os.R_OK)), "open", call(lambda: os.close(os.open(p, os.O_RDONLY))),
              "xattr", call(lambda: os.listxattr(p)), "chmod", call(lambda: os.chmod(p, 0o600)),
              "utime", call(lambda: os.utime(p)), "unlink", call(lambda: os.unlink(p)), "mkdir", call(lambda: os.mkdir(p)),
              "readlink", call(lambda: os.readlink(p)), "realpath", realpath(p))


def slash_section():
    print("== trailing slashes")
    os.makedirs(FX + "/sl/dir", exist_ok=True)
    open(FX + "/sl/file", "w").write("x")
    os.symlink("dir", FX + "/sl/ldir")
    os.symlink("file", FX + "/sl/lfile")
    os.symlink("nowhere", FX + "/sl/ldangling")
    for name in ("missing", "dir", "file", "ldir", "lfile", "ldangling", "missing/deeper", "file/deeper"):
        p = FX + "/sl/" + name + "/"
        print(" ", name + "/", "stat", call(lambda: mode_of(os.stat(p))), "lstat", call(lambda: stat.S_IFMT(os.lstat(p).st_mode)),
              "access", call(lambda: os.access(p, os.F_OK)), "open", call(lambda: os.close(os.open(p, os.O_RDONLY))),
              "realpath", realpath(p))
    t = lambda label, fn: print(f"  {label}: {call(fn)}")
    t("create missing/", lambda: os.close(os.open(FX + "/sl/new/", os.O_CREAT | os.O_WRONLY, 0o644)))
    t("create dir/", lambda: os.close(os.open(FX + "/sl/dir/", os.O_CREAT | os.O_WRONLY, 0o644)))
    t("mkdir new/", lambda: os.mkdir(FX + "/sl/made/"))
    t("made", lambda: mode_of(os.stat(FX + "/sl/made")))
    t("mkdir file/", lambda: os.mkdir(FX + "/sl/file/"))
    t("rmdir made/", lambda: os.rmdir(FX + "/sl/made/"))
    t("unlink file/", lambda: os.unlink(FX + "/sl/file/"))
    s = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
    t("bind sock/", lambda: s.bind(FX + "/sl/sock/"))
    t("connect missing/", lambda: s.connect(FX + "/sl/missing/"))
    s.close()
    for name in ("empty", "empty2", "full/inner"):
        os.makedirs(FX + "/sl/" + name, exist_ok=True)
    os.chdir(FX + "/sl/empty")
    for p in (".", "./", "..", "../empty/.", "../empty/..", "../empty2/"):
        t(f"rmdir {p}", lambda: os.rmdir(p))
        t(f"unlink {p}", lambda: os.unlink(p))
    os.chdir(FX)
    for name in ("ldir/", "lfile/", "file/", "missing/", "full/", "full//", "empty//", "full/inner/../"):
        t(f"rmdir {name}", lambda: os.rmdir(FX + "/sl/" + name))
    r, w = os.pipe()
    t("stat at a pipe", lambda: os.stat("x", dir_fd=r))
    t("access at a pipe", lambda: os.access("x", os.F_OK, dir_fd=r))
    t("open at a pipe", lambda: os.open("x", os.O_RDONLY, dir_fd=r))
    t("mkdir at a pipe", lambda: os.mkdir("x", dir_fd=r))
    t("readlink at a pipe", lambda: os.readlink("x", dir_fd=r))
    os.close(r)
    os.close(w)
    t("listing", lambda: sorted(os.listdir(FX + "/sl")))
    os.symlink("/usr/local/lib/directaudio/linux-wine11/lib/wine/aarch64-windows/winedirectaudio.drv", FX + "/sl/long")
    os.symlink(ROOT + "/usr/local/lib/directaudio/linux-wine11/lib/wine/aarch64-windows/x.drv", FX + "/sl/longhost")
    os.symlink(ROOT + "x/y", FX + "/sl/rootprefix")
    for name in ("long", "longhost", "rootprefix"):
        p = FX + "/sl/" + name
        print(" ", name, " ".join(f"{n}:{raw_readlink(p, n)}" for n in (1, 2, len(ROOT), len(ROOT) + 1, len(ROOT) + 2, 30, 64, 128)))


def accept_section():
    print("== accept")
    path = "\0fp-equiv3-listener"
    srv = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
    srv.bind(path)
    srv.listen(4)
    named = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
    named.bind("\0fp-equiv3-client")
    named.connect(path)
    plain = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
    plain.connect(path)
    for label in ("named", "plain"):
        c, addr = srv.accept()
        print(" ", label, "blocking", srv.getblocking(), "addr", repr(addr), "name", repr(c.getsockname()),
              "peer", call(c.getpeername))
        c.close()
    srv.setblocking(False)
    print("  none waiting", call(srv.accept))
    for x in (srv, named, plain):
        x.close()


def deep_section():
    print("== long host paths")
    os.makedirs(DEEP)
    t = lambda label, fn: print(f"  {label}: {call(fn)}")
    t("stat missing", lambda: os.stat(DEEP + "/missing.dll"))
    t("access missing", lambda: os.access(DEEP + "/missing.dll", os.F_OK))
    t("create", lambda: os.close(os.open(DEEP + "/new", os.O_CREAT | os.O_WRONLY, 0o644)))
    t("mkdir", lambda: os.mkdir(DEEP + "/sub"))
    t("symlink", lambda: os.symlink("new", DEEP + "/sub/link"))
    t("stat through", lambda: mode_of(os.stat(DEEP + "/sub/link")))
    t("readlink", lambda: os.readlink(DEEP + "/sub/link"))
    t("rename", lambda: os.rename(DEEP + "/new", DEEP + "/renamed"))
    t("unlink missing", lambda: os.unlink(DEEP + "/new"))
    t("unlink", lambda: os.unlink(DEEP + "/renamed"))
    t("realpath", lambda: realpath(DEEP + "/sub/../sub/link"))
    t("listing", lambda: sorted(os.listdir(DEEP)))


def glue_section():
    print("== glue above a binding")
    for p in ("/cache", "/cache/fp", "/cache/fp/perf", "/cache/fp/other", "/cache/other", "/cache/fp/perf/x",
              "/cache/fp/.", "/cache/fp/..", "/cache/fp/../fp/perf", "/cache/fp/./perf", "/cache/fp/", "/cache/fp/perf/",
              "/sys/class/..", "/sys/class/../class", "/proc/self/..", "/opt/fpx/.."):
        print(" ", p, "stat", call(lambda: mode_of(os.stat(p))), "read", call(lambda: open(p, "rb").read(4)),
              "realpath", realpath(p))


def shm_section():
    print("== shm_open")

    def shm(name, flags, mode=0o600):
        ctypes.set_errno(0)
        fd = libc.shm_open(name, flags, mode)
        if fd < 0:
            return err()
        st = os.fstat(fd)
        os.close(fd)
        return (stat.S_IFMT(st.st_mode), oct(st.st_mode & 0o777), st.st_size)

    def unlink(name):
        ctypes.set_errno(0)
        return "ok" if libc.shm_unlink(name) == 0 else err()

    os.makedirs("/dev/shm/fp-dir", exist_ok=True)
    rw, creat, excl = os.O_RDWR, os.O_CREAT, os.O_EXCL
    for label, args in [("create", (b"/fp-eq", rw | creat)), ("again", (b"fp-eq", rw)), ("excl", (b"/fp-eq", rw | creat | excl)),
                        ("slashes", (b"///fp-eq", os.O_RDONLY)), ("missing", (b"/fp-none", rw)), ("empty", (b"", rw)),
                        ("only slash", (b"/", rw)), ("inner slash", (b"/a/b", rw | creat)), ("dir", (b"fp-dir", os.O_RDONLY)),
                        ("long", (b"x" * 300, rw | creat))]:
        print(" ", label, shm(*args))
    print("  unlink", unlink(b"/fp-eq"), "again", unlink(b"/fp-eq"), "bad", unlink(b"a/b"), "dir", unlink(b"fp-dir"))
    os.rmdir("/dev/shm/fp-dir")


def uname_section():
    print("== uname")
    print("  uname", tuple(os.uname()), "again", tuple(os.uname()) == tuple(os.uname()))
    print("  hostname", socket.gethostname())
    for n in (0, 1, 4, 64, 65, 300):
        buf = ctypes.create_string_buffer(b"#" * (n + 1), n + 1)
        ctypes.set_errno(0)
        r = libc.gethostname(buf, n)
        print(f"  gethostname {n}", r, err() if r else "", buf.raw[: n + 1])
    sys.stdout.flush()
    pid = os.fork()
    if pid == 0:
        print("  child", tuple(os.uname()) == tuple(os.uname()), socket.gethostname(), flush=True)
        os._exit(0)
    os.waitpid(pid, 0)


def isatty_section():
    print("== isatty")
    libc.isatty.argtypes = [ctypes.c_int]
    r, w = os.pipe()
    fds = {"pipe": r, "dev null": os.open("/dev/null", os.O_RDONLY), "file": os.open("/etc/os-release", os.O_RDONLY),
           "dir": os.open("/", os.O_RDONLY), "socket": socket.socket().detach(), "closed": 999, "negative": -1}
    try:
        m, sl = os.openpty()
        fds.update({"pty master": m, "pty slave": sl})
    except OSError as e:
        print("  openpty", errno.errorcode.get(e.errno))
    for label, fd in fds.items():
        ctypes.set_errno(0)
        print(" ", label, libc.isatty(fd), err(), "os", call(lambda: os.isatty(fd)))
    for fd in fds.values():
        try:
            os.close(fd)
        except OSError:
            pass
    os.close(w)


PASSWD = """# comment

root:x:0:0:root:/root:/bin/bash
alice:x:1000:1000:Alice A,,,:/home/alice:/bin/sh
bob:*:1001:100::/home/bob:
"""
PASSWD_VARIANTS = {
    "plain": ("passwd: files systemd\ngroup: files\n", PASSWD),
    "extra colon": ("passwd: files systemd\n", PASSWD + "carol:x:1002:1002::/home/carol:/bin/sh:extra\n"),
    "bad uid": ("passwd: files\n", "bad:x:abc:1::/:/bin/sh\n" + PASSWD + "dave:x:1003:1003::/:/bin/sh"),
    "big uid": ("passwd: files\n", PASSWD + "eve:x:0010:4294967296::/:/bin/sh\n"),
    "compat": ("passwd: files\n", "+@staff\n-bob\n" + PASSWD),
    "leading space": ("passwd: files\n", " root:x:0:0:root:/root:/bin/bash\n" + PASSWD),
    "duplicate": ("passwd: files\n", "alice:x:1001:1:dup:/:/bin/false\n" + PASSWD),
    "cr": ("passwd: files\n", PASSWD.replace("\n", "\r\n")),
    "action": ("passwd: files [NOTFOUND=return] systemd\n", PASSWD),
    "files second": ("passwd: systemd files\n", PASSWD),
    "comment": ("passwd: files # local\n", PASSWD),
    "none": ("group: files\n", PASSWD),
    "two lines": ("passwd: files\npasswd: systemd\n", PASSWD),
}


class Passwd(ctypes.Structure):
    _fields_ = [("name", ctypes.c_char_p), ("passwd", ctypes.c_char_p), ("uid", ctypes.c_uint),
                ("gid", ctypes.c_uint), ("gecos", ctypes.c_char_p), ("dir", ctypes.c_char_p),
                ("shell", ctypes.c_char_p)]


def lookup(fn):
    try:
        return repr(tuple(fn()))
    except (KeyError, OSError) as e:
        return type(e).__name__


def show_pw(p):
    return "NULL" if not p else repr(tuple(getattr(p.contents, f) for f, _ in Passwd._fields_))


def passwd_probe():
    import pwd
    libc.getpwnam.restype = libc.getpwuid.restype = ctypes.POINTER(Passwd)
    libc.getpwnam.argtypes = [ctypes.c_char_p]
    libc.getpwuid.argtypes = [ctypes.c_uint]
    names = ["root", "alice", "bob", "carol", "dave", "eve", "nobody", "+@staff", "-bob", ""]
    uids = [0, 10, 100, 1000, 1001, 1002, 1003, 65534, 4294967295]
    for n in names:
        ctypes.set_errno(0)
        r = libc.getpwnam(n.encode())
        print(f"    getpwnam {n!r}", show_pw(r), err(), lookup(lambda: pwd.getpwnam(n)) if n else "")
    for u in uids:
        ctypes.set_errno(0)
        r = libc.getpwuid(u)
        print(f"    getpwuid {u}", show_pw(r), err(), lookup(lambda: pwd.getpwuid(u)))
    a = libc.getpwnam(b"root")
    b = libc.getpwnam(b"alice")
    print("    static reused", bool(a) and bool(b) and ctypes.addressof(a.contents) == ctypes.addressof(b.contents),
          show_pw(a))
    for fn, key in ((libc.getpwnam_r, b"alice"), (libc.getpwuid_r, 1000), (libc.getpwnam_r, b"nobody")):
        for size in (1, 8, 40, 46, 47, 48, 49, 50, 64, 1024):
            pw, res, buf = Passwd(), ctypes.POINTER(Passwd)(), ctypes.create_string_buffer(size)
            ctypes.set_errno(0)
            r = fn(key, ctypes.byref(pw), buf, size, ctypes.byref(res))
            print(f"    {fn.__name__} {key!r} {size}", errno.errorcode.get(r, r), err(), show_pw(res))
    sys.stdout.flush()


def passwd_section():
    print("== passwd")
    saved = {}
    for f in ("/etc/passwd", "/etc/nsswitch.conf"):
        try:
            saved[f] = open(f).read()
        except OSError:
            saved[f] = None
    try:
        for label, (conf, passwd) in PASSWD_VARIANTS.items():
            print(" ", label, flush=True)
            open("/etc/nsswitch.conf", "w").write(conf)
            open("/etc/passwd", "w").write(passwd)
            pid = os.fork()
            if pid == 0:
                os.execv(sys.executable, [sys.executable, __file__, "passwd"])
            os.waitpid(pid, 0)
    finally:
        for f, text in saved.items():
            if text is None:
                os.unlink(f)
            else:
                open(f, "w").write(text)


def fortify_section():
    print("== fortify")
    os.chdir(FX + "/dir")
    buf = ctypes.create_string_buffer(256)
    ctypes.set_errno(0)
    r = libc.__getcwd_chk(buf, 256, 256)
    print("  getcwd_chk", norm(r.decode()) if r else err())
    ctypes.set_errno(0)
    r = libc.__getcwd_chk(buf, 4, 256)
    print("  getcwd_chk small", norm(r.decode()) if r else err())
    d = os.open(FX, os.O_RDONLY)
    print("  readlinkat_chk", libc.__readlinkat_chk(d, b"mtab", buf, 256, 256), norm(buf.value))
    os.close(d)
    os.chdir("/")


def main():
    shutil.rmtree(FX, ignore_errors=True)
    os.makedirs(FX)
    os.chdir(FX)
    proc_section()
    host_text_section()
    shared_section()
    unseen_section()
    slash_section()
    accept_section()
    deep_section()
    glue_section()
    shm_section()
    uname_section()
    isatty_section()
    passwd_section()
    fortify_section()
    shutil.rmtree(FX, ignore_errors=True)


if __name__ == "__main__":
    passwd_probe() if sys.argv[1:] == ["passwd"] else main()
