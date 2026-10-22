import array
import mmap
import os
import socket
import struct
import subprocess
import tempfile
import threading
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
PROTO = ROOT / 'app/src/main/cpp/venc/venc_proto.h'
VENC = ROOT / 'tools/linuxfs/preload/venc.c'
TOUCH = ROOT / 'tools/linuxfs/preload/rptouch.c'

DRIVER = r'''
#include <errno.h>
#include <linux/videodev2.h>
#include <stdio.h>
#include <string.h>
#include <sys/mman.h>

int bl_venc_open(const char *path, int flags);
int bl_venc_ioctl(int fd, unsigned long req, void *arg, int *rc);
int bl_venc_mmap(void *addr, size_t len, int prot, int flags, int fd, off_t off,
                 void *(*real)(void *, size_t, int, int, int, off_t), void **out);
void bl_venc_closed(int fd);

static int fd;
static int call(unsigned long req, void *arg) {
    int rc;
    if (!bl_venc_ioctl(fd, req, arg, &rc)) return -100;
    return rc < 0 ? -errno : 0;
}

int main(void) {
    if (bl_venc_open("/dev/video0", 0) != -2) return 2;
    fd = bl_venc_open("/dev/video-enc0", 0);
    if (fd < 0) return 3;
    struct v4l2_capability cap;
    if (call(VIDIOC_QUERYCAP, &cap) || !(cap.device_caps & V4L2_CAP_VIDEO_M2M_MPLANE)) return 4;
    struct v4l2_format f = { .type = V4L2_BUF_TYPE_VIDEO_OUTPUT_MPLANE };
    f.fmt.pix_mp.width = 640; f.fmt.pix_mp.height = 360; f.fmt.pix_mp.pixelformat = V4L2_PIX_FMT_NV12;
    if (call(VIDIOC_S_FMT, &f) || f.fmt.pix_mp.plane_fmt[0].sizeimage != 640 * 360 * 3 / 2) return 5;
    f = (struct v4l2_format){ .type = V4L2_BUF_TYPE_VIDEO_CAPTURE_MPLANE };
    f.fmt.pix_mp.pixelformat = V4L2_PIX_FMT_HEVC;
    if (call(VIDIOC_S_FMT, &f) || f.fmt.pix_mp.pixelformat != V4L2_PIX_FMT_HEVC) return 6;
    struct v4l2_control c = { V4L2_CID_MPEG_VIDEO_BITRATE, 8000000 };
    if (call(VIDIOC_S_CTRL, &c)) return 7;
    unsigned types[2] = { V4L2_BUF_TYPE_VIDEO_OUTPUT_MPLANE, V4L2_BUF_TYPE_VIDEO_CAPTURE_MPLANE };
    unsigned char *maps[2][2];
    for (int t = 0; t < 2; t++) {
        struct v4l2_requestbuffers r = { .count = 2, .type = types[t], .memory = V4L2_MEMORY_MMAP };
        if (call(VIDIOC_REQBUFS, &r) || r.count != 2) return 8;
        for (int i = 0; i < 2; i++) {
            struct v4l2_plane p;
            struct v4l2_buffer b = { .index = i, .type = types[t], .memory = V4L2_MEMORY_MMAP, .length = 1, .m.planes = &p };
            if (call(VIDIOC_QUERYBUF, &b)) return 9;
            void *m;
            if (!bl_venc_mmap(NULL, p.length, PROT_READ | PROT_WRITE, MAP_SHARED, fd, p.m.mem_offset, mmap, &m) ||
                m == MAP_FAILED) return 10;
            maps[t][i] = m;
            if (t == 1 && call(VIDIOC_QBUF, &b)) return 11;
        }
        if (call(VIDIOC_STREAMON, &types[t])) return 12;
    }
    struct v4l2_plane cp;
    struct v4l2_buffer cb = { .type = types[1], .memory = V4L2_MEMORY_MMAP, .length = 1, .m.planes = &cp };
    if (call(VIDIOC_DQBUF, &cb) != -EAGAIN) return 13;
    for (int frame = 0; frame < 3; frame++) {
        memset(maps[0][frame % 2], 'a' + frame, 640 * 360);
        struct v4l2_plane p = { .bytesused = 640 * 360 * 3 / 2 };
        struct v4l2_buffer b = { .index = frame % 2, .type = types[0], .memory = V4L2_MEMORY_MMAP, .length = 1, .m.planes = &p };
        b.timestamp.tv_sec = frame;
        if (frame == 1) {
            struct v4l2_control k = { V4L2_CID_MPEG_VIDEO_FORCE_KEY_FRAME, 1 };
            call(VIDIOC_S_CTRL, &k);
        }
        if (call(VIDIOC_QBUF, &b)) return 14;
        if (call(VIDIOC_DQBUF, &cb)) return 15;
        printf("%d %u %d %ld %.*s\n", frame, cp.bytesused, !!(cb.flags & V4L2_BUF_FLAG_KEYFRAME),
               (long)cb.timestamp.tv_sec, (int)cp.bytesused, maps[1][cb.index]);
        if (call(VIDIOC_DQBUF, &b)) return 16;
        if (call(VIDIOC_QBUF, &cb)) return 17;
    }
    bl_venc_closed(fd);
    return 0;
}
'''

TOUCH_DRIVER = r'''
#include <stdio.h>
#include <stdlib.h>
#include <sys/wait.h>
#include <unistd.h>

int bl_venc_open(const char *path, int flags);
void bl_venc_closed(int fd);
int XTestFakeMotionEvent(void *dpy, int screen, int x, int y, unsigned long delay);
int XTestFakeButtonEvent(void *dpy, unsigned int button, int press, unsigned long delay);
int XDisplayWidth(void *dpy, int screen) { return 1281; }
int XDisplayHeight(void *dpy, int screen) { return 721; }

int main(void) {
    XTestFakeButtonEvent(NULL, 1, 1, 0);
    XTestFakeButtonEvent(NULL, 1, 0, 0);
    int fd = bl_venc_open("/dev/video-enc0", 0);
    if (fd < 0) return 3;
    XTestFakeMotionEvent(NULL, 0, 1280, 360, 0);
    XTestFakeButtonEvent(NULL, 1, 1, 0);
    XTestFakeMotionEvent(NULL, 0, 640, 0, 0);
    XTestFakeButtonEvent(NULL, 3, 1, 0);
    XTestFakeButtonEvent(NULL, 1, 0, 0);
    char marker[512];
    snprintf(marker, sizeof marker, "%s/droiddeck-remote-play", getenv("XDG_RUNTIME_DIR"));
    if (access(marker, F_OK) != 0) return 4;
    pid_t child = fork();
    if (child == 0) { bl_venc_closed(fd); _exit(access(marker, F_OK) == 0 ? 0 : 1); }
    int status;
    waitpid(child, &status, 0);
    if (!WIFEXITED(status) || WEXITSTATUS(status) != 0) return 5;
    bl_venc_closed(fd);
    if (access(marker, F_OK) == 0) return 6;
    XTestFakeButtonEvent(NULL, 1, 1, 0);
    usleep(200000);
    return 0;
}
'''

CONFIG = struct.Struct('<' + 'I' * 12 + 'ii' + 'I' * 4)
FRAME = struct.Struct('<IIIII4xq')
DONE = struct.Struct('<IiII')


class FakeEncoder(threading.Thread):
    def __init__(self, path):
        super().__init__(daemon=True)
        self.listener = socket.socket(socket.AF_UNIX, socket.SOCK_SEQPACKET)
        self.listener.bind(path)
        self.listener.listen(1)
        self.configs, self.frames, self.touches = [], [], []

    def run(self):
        while True:
            try:
                conn, _ = self.listener.accept()
            except OSError:
                return
            threading.Thread(target=self.serve, args=(conn,), daemon=True).start()

    def serve(self, conn):
        outputs = []
        while True:
            fds = array.array('i')
            msg, ancdata, _, _ = conn.recvmsg(256, socket.CMSG_LEN(32 * fds.itemsize))
            if not msg:
                return
            for level, kind, data in ancdata:
                if level == socket.SOL_SOCKET and kind == socket.SCM_RIGHTS:
                    fds.frombytes(data[:len(data) - len(data) % fds.itemsize])
            kind = struct.unpack_from('<I', msg)[0]
            if kind == 5:
                self.touches.append(struct.unpack('<IIII', msg)[1:])
            elif kind == 1:
                c = CONFIG.unpack(msg)
                self.configs.append(c)
                inputs, size = c[14], c[17]
                outputs = [mmap.mmap(fd, size) for fd in fds[inputs:]]
                for fd in fds:
                    os.close(fd)
                conn.send(DONE.pack(3, 0, 0, 0))
            elif kind == 2:
                _, _, output, nbytes, key, pts = FRAME.unpack(msg)
                self.frames.append((nbytes, key, pts))
                payload = b'frame%d' % len(self.frames)
                outputs[output][:len(payload)] = payload
                conn.send(DONE.pack(3, 0, len(payload), 1 if key or len(self.frames) == 1 else 0))


class VencEmulatorTest(unittest.TestCase):
    def test_steam_style_session_round_trips_through_the_app_socket(self):
        with tempfile.TemporaryDirectory() as tmp:
            tmp = Path(tmp)
            driver = tmp / 'driver.c'
            driver.write_text(DRIVER)
            binary = tmp / 'driver'
            subprocess.run(['cc', '-O1', '-pthread', '-o', str(binary), str(driver), str(VENC), '-ldl'],
                           check=True)
            sock = tmp / 'venc.sock'
            server = FakeEncoder(str(sock))
            self.addCleanup(server.listener.close)
            server.start()
            done = subprocess.run([str(binary)], env=dict(os.environ, BL_VENC_SOCKET=str(sock)),
                                  capture_output=True, text=True, timeout=20)
            self.assertEqual(done.returncode, 0, done.stdout + done.stderr)
            self.assertEqual(done.stdout.split('\n')[:3],
                             ['0 6 1 0 frame1', '1 6 1 1 frame2', '2 6 0 2 frame3'])
            self.assertEqual(len(server.configs), 1)
            config = server.configs[0]
            self.assertEqual(config[1], 1)
            self.assertEqual(config[2:6], (640, 360, 640, 360))
            self.assertEqual(config[8], 8000000)
            self.assertEqual([f[1] for f in server.frames], [0, 1, 0])
            self.assertEqual([f[2] for f in server.frames], [0, 1000000, 2000000])
            self.assertTrue(all(f[0] == 640 * 360 * 3 // 2 for f in server.frames))

    def test_remote_touch_and_stream_marker_follow_the_streaming_process(self):
        with tempfile.TemporaryDirectory() as tmp:
            tmp = Path(tmp)
            driver = tmp / 'touch.c'
            driver.write_text(TOUCH_DRIVER)
            binary = tmp / 'touch'
            subprocess.run(['cc', '-O1', '-pthread', '-rdynamic', '-o', str(binary), str(driver), str(VENC), str(TOUCH),
                            '-ldl'], check=True)
            sock = tmp / 'venc.sock'
            server = FakeEncoder(str(sock))
            self.addCleanup(server.listener.close)
            server.start()
            done = subprocess.run([str(binary)], env=dict(os.environ, BL_VENC_SOCKET=str(sock), XDG_RUNTIME_DIR=str(tmp)),
                                  capture_output=True, text=True, timeout=20)
            self.assertEqual(done.returncode, 0, done.stderr)
            self.assertEqual(server.touches, [(0, 65535, 32767), (1, 32767, 0), (2, 32767, 0)])


if __name__ == '__main__':
    unittest.main()
