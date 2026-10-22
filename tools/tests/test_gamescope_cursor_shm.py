#!/usr/bin/env python3
"""Live regression probe; run inside a running Gamescope session.

Requires Python 3, WAYLAND_DISPLAY, XDG_RUNTIME_DIR and access to Gamescope's
/proc/<pid>/fd. Creates only a temporary surface with the cursor role; it does
not take pointer focus or change game/save files. Affected Gamescope retains all
20 SHM allocations; fixed Gamescope retains at most the current cursor buffer.
"""
import os
import socket
import struct
import time
import json
import array
import argparse
from pathlib import Path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--unassigned-control', action='store_true', help='verify that window buffers awaiting association remain queued')
    args = parser.parse_args()
    s = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
    s.settimeout(2)
    s.connect(os.path.join(os.environ['XDG_RUNTIME_DIR'], os.environ['WAYLAND_DISPLAY']))
    U = lambda *v: struct.pack('<' + 'I' * len(v), *v)

    def string(v):
        b = v.encode() + b'\x00'
        return U(len(b)) + b + b'\x00' * (-len(b) % 4)

    def send(obj, op, p=b'', fd=None):
        b = U(obj, len(p) + 8 << 16 | op) + p
        if fd is None:
            s.sendall(b)
        else:
            s.sendmsg([b], [(socket.SOL_SOCKET, socket.SCM_RIGHTS, array.array('i', [fd]))])
    nextid = 2
    pids = []
    for process in Path('/proc').iterdir():
        if not process.name.isdigit():
            continue
        try:
            if (process / 'comm').read_text().strip() == 'gamescope':
                pids.append(process)
        except (FileNotFoundError, PermissionError):
            pass
    assert len(pids) == 1, f'expected one Gamescope process, found {[p.name for p in pids]}'
    fd_dir = pids[0] / 'fd'

    def alloc():
        nonlocal nextid
        nextid += 1
        return nextid
    pending = b''
    globals = {}

    def roundtrip():
        nonlocal pending
        cb = alloc()
        send(1, 0, U(cb))
        while True:
            while len(pending) >= 8:
                obj, h = struct.unpack_from('<II', pending)
                n = h >> 16
                op = h & 65535
                if len(pending) < n:
                    break
                p = pending[8:n]
                pending = pending[n:]
                if obj == 1 and op == 0:
                    raise RuntimeError('wayland error ' + repr(p))
                if obj == 2 and op == 0:
                    name, l = struct.unpack_from('<II', p)
                    itf = p[8:8 + l - 1].decode()
                    ver = struct.unpack_from('<I', p, 8 + (l + 3) // 4 * 4)[0]
                    globals[itf] = (name, ver)
                if obj == cb:
                    return
            chunk = s.recv(65536)
            if not chunk:
                raise RuntimeError('Wayland connection closed during roundtrip')
            pending += chunk

    def bind(itf, version):
        obj = alloc()
        name, ver = globals[itf]
        send(2, 0, U(name) + string(itf) + U(min(ver, version), obj))
        return obj

    def count():
        n = 0
        for f in fd_dir.iterdir():
            try:
                n += 'dd-shm-probe' in os.readlink(f)
            except FileNotFoundError:
                pass
        return n
    send(1, 1, U(2))
    roundtrip()
    comp = bind('wl_compositor', 4)
    shm = bind('wl_shm', 1)
    seat = bind('wl_seat', 1)
    pointer = alloc()
    send(seat, 0, U(pointer))
    surface = alloc()
    send(comp, 0, U(surface))
    if not args.unassigned_control:
        # wlroots 0.20.2 assigns the cursor role before forwarding this serial
        # to Gamescope, so zero avoids needing pointer focus but still tests the
        # role guard in xwayland_surface_commit.
        send(pointer, 0, U(0, surface, 0, 0))
    roundtrip()
    before = count()
    for i in range(20):
        fd = os.memfd_create('dd-shm-probe', os.MFD_CLOEXEC)
        os.ftruncate(fd, 64 * 64 * 4)
        pool = alloc()
        send(shm, 0, U(pool, 64 * 64 * 4), fd)
        os.close(fd)
        buffer = alloc()
        send(pool, 0, U(buffer, 0, 64, 64, 64 * 4, 0))
        send(pool, 1)
        send(surface, 1, U(buffer, 0, 0))
        send(surface, 2, U(0, 0, 64, 64))
        send(surface, 6)
        send(buffer, 0)
        roundtrip()
    after_commits = count()
    send(surface, 0)
    roundtrip()
    time.sleep(0.2)
    after_surface_destroy = count()
    s.close()
    print(json.dumps(dict(before=before, after_commits=after_commits, after_surface_destroy=after_surface_destroy, growth=after_commits - before)))
    if args.unassigned_control:
        assert after_commits - before == 20, 'unassigned window commits were unexpectedly discarded'
    else:
        assert after_commits - before <= 1, f'cursor buffers leaked: {after_commits - before} retained after 20 commits'
    assert after_surface_destroy == before, 'cursor buffers remain after destroying the test surface'


if __name__ == "__main__":
    main()
