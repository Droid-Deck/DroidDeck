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
VDEC = ROOT / 'tools/linuxfs/preload/vdec.c'
VENC = ROOT / 'tools/linuxfs/preload/venc.c'

DRIVER = r'''
#define _GNU_SOURCE
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <linux/videodev2.h>
#include <poll.h>
#include <stdarg.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/mman.h>
#include <unistd.h>

int bl_vdec_open(const char *path, int flags);
int bl_vdec_ioctl(int fd, unsigned long req, void *arg, int *rc);
void bl_vdec_closed(int fd);
void *SDL_LoadObject(const char *name);
void *SDL_LoadFunction(void *handle, const char *name);

struct heap_alloc { uint64_t len; uint32_t fd, fd_flags; uint64_t heap_flags; };
struct create_dumb { uint32_t height, width, bpp, flags, handle, pitch; uint64_t size; };
struct prime_handle { uint32_t handle, flags; int32_t fd; };
struct destroy_dumb { uint32_t handle; };
struct drm_device { char **nodes; int available_nodes; };

static int heap = -1;

int open(const char *path, int flags, ...) {
    static int (*real)(const char *, int, ...);
    va_list ap;
    va_start(ap, flags);
    int mode = va_arg(ap, int);
    va_end(ap);
    if (!real) real = dlsym(RTLD_NEXT, "open");
    if (strcmp(path, "/dev/dma_heap/system") == 0) return heap = memfd_create("heap", 0);
    return real(path, flags, mode);
}

int ioctl(int fd, unsigned long req, ...) {
    static int (*real)(int, unsigned long, ...);
    va_list ap;
    va_start(ap, req);
    void *arg = va_arg(ap, void *);
    va_end(ap);
    if (!real) real = dlsym(RTLD_NEXT, "ioctl");
    if (fd == heap && req == _IOWR('H', 0, struct heap_alloc)) {
        struct heap_alloc *a = arg;
        int buf = memfd_create("dmabuf", 0);
        if (ftruncate(buf, (off_t)a->len) != 0) return -1;
        a->fd = (uint32_t)buf;
        return 0;
    }
    if (req == _IOW('b', 0, uint64_t)) return 0;
    return real(fd, req, arg);
}

static int vfd;
static int call(unsigned long req, void *arg) {
    int rc;
    if (!bl_vdec_ioctl(vfd, req, arg, &rc)) return -100;
    return rc < 0 ? -errno : 0;
}

int main(void) {
    if (bl_vdec_open("/dev/video0", O_RDWR) != -2) return 2;
    void *drm = SDL_LoadObject("libdrm.so.2");
    if (!drm) return 3;
    int (*devices)(uint32_t, struct drm_device **, int) = SDL_LoadFunction(drm, "drmGetDevices2");
    int (*drm_ioctl)(int, unsigned long, void *) = SDL_LoadFunction(drm, "drmIoctl");
    if (!devices || !drm_ioctl || !SDL_LoadFunction(drm, "drmModeAtomicCommit")) return 4;
    struct drm_device *dev[1];
    if (devices(0, NULL, 0) != 1 || devices(0, dev, 1) != 1 || !(dev[0]->available_nodes & 1)) return 5;
    int card = open(dev[0]->nodes[0], O_RDWR | O_CLOEXEC);
    if (card < 0) return 6;

    vfd = bl_vdec_open("/dev/video-dec0", O_RDWR);
    if (vfd < 0) return 7;
    struct v4l2_capability cap;
    if (call(VIDIOC_QUERYCAP, &cap) || (cap.capabilities & 0x04004000) != 0x04004000) return 8;
    struct v4l2_fmtdesc d = { .index = 1, .type = V4L2_BUF_TYPE_VIDEO_OUTPUT_MPLANE };
    if (call(VIDIOC_ENUM_FMT, &d) || d.pixelformat != V4L2_PIX_FMT_HEVC) return 9;
    struct v4l2_format f = { .type = V4L2_BUF_TYPE_VIDEO_OUTPUT_MPLANE };
    f.fmt.pix_mp.width = 1280; f.fmt.pix_mp.height = 720; f.fmt.pix_mp.pixelformat = V4L2_PIX_FMT_HEVC;
    if (call(VIDIOC_S_FMT, &f)) return 10;
    struct v4l2_requestbuffers r = { .count = 4, .type = V4L2_BUF_TYPE_VIDEO_OUTPUT_MPLANE, .memory = V4L2_MEMORY_MMAP };
    if (call(VIDIOC_REQBUFS, &r) || r.count != 4) return 11;
    unsigned char *in[4];
    for (int i = 0; i < 4; i++) {
        struct v4l2_plane p = { 0 };
        struct v4l2_buffer b = { .index = i, .type = r.type, .memory = V4L2_MEMORY_MMAP, .length = 1, .m.planes = &p };
        if (call(VIDIOC_QUERYBUF, &b)) return 12;
        in[i] = mmap(NULL, p.length, PROT_READ | PROT_WRITE, MAP_SHARED, vfd, p.m.mem_offset);
        if (in[i] == MAP_FAILED) return 13;
    }
    f = (struct v4l2_format){ .type = V4L2_BUF_TYPE_VIDEO_CAPTURE_MPLANE };
    f.fmt.pix_mp.width = 1280; f.fmt.pix_mp.height = 736; f.fmt.pix_mp.pixelformat = V4L2_PIX_FMT_NV12;
    if (call(VIDIOC_S_FMT, &f) || f.fmt.pix_mp.num_planes != 1) return 14;
    uint32_t bpl = f.fmt.pix_mp.plane_fmt[0].bytesperline, size = f.fmt.pix_mp.plane_fmt[0].sizeimage;
    if (bpl != 1280 || size != 1280 * 736 * 3 / 2) return 15;
    r = (struct v4l2_requestbuffers){ .count = 3, .type = V4L2_BUF_TYPE_VIDEO_CAPTURE_MPLANE, .memory = V4L2_MEMORY_DMABUF };
    if (call(VIDIOC_REQBUFS, &r) || r.count != 3) return 16;
    int out[3];
    for (int i = 0; i < 3; i++) {
        struct create_dumb c = { .height = 736, .width = 1280, .bpp = 16 };
        if (drm_ioctl(card, 0xc02064b2, &c) || !c.handle || c.size < size) return 17;
        struct prime_handle h = { .handle = c.handle, .flags = O_CLOEXEC };
        if (drm_ioctl(card, 0xc00c642d, &h)) return 18;
        struct destroy_dumb x = { .handle = c.handle };
        if (drm_ioctl(card, 0xc00464b4, &x)) return 19;
        out[i] = h.fd;
    }
    unsigned types[2] = { V4L2_BUF_TYPE_VIDEO_OUTPUT_MPLANE, V4L2_BUF_TYPE_VIDEO_CAPTURE_MPLANE };
    if (call(VIDIOC_STREAMON, &types[0]) || call(VIDIOC_STREAMON, &types[1])) return 20;
    for (int i = 0; i < 3; i++) {
        struct v4l2_plane p = { .m.fd = out[i] };
        struct v4l2_buffer b = { .index = i, .type = types[1], .memory = V4L2_MEMORY_DMABUF, .length = 1, .m.planes = &p };
        if (call(VIDIOC_QBUF, &b)) return 21;
    }
    struct pollfd pf = { .fd = vfd, .events = 0xc9 };
    if (poll(&pf, 1, 0) != 0) return 22;
    for (int frame = 1; frame <= 5; frame++) {
        int slot = frame % 4;
        memset(in[slot], 'a' + frame, 100 + frame);
        struct v4l2_plane p = { .bytesused = 100 + frame };
        struct v4l2_buffer b = { .index = slot, .type = types[0], .memory = V4L2_MEMORY_MMAP, .flags = 0x4008, .length = 1, .m.planes = &p };
        b.timestamp.tv_usec = frame;
        if (call(VIDIOC_QBUF, &b)) return 23;
        pf = (struct pollfd){ .fd = vfd, .events = 0xc9 };
        if (poll(&pf, 1, 30) != 1 || !(pf.revents & POLLIN)) return 24;
        struct v4l2_plane cp = { 0 };
        struct v4l2_buffer cb = { .type = types[1], .memory = V4L2_MEMORY_DMABUF, .length = 1, .m.planes = &cp };
        if (call(VIDIOC_DQBUF, &cb)) return 25;
        unsigned char *m = mmap(NULL, size, PROT_READ, MAP_SHARED, out[cb.index], 0);
        printf("%ld %u %c%c %u\n", (long)cb.timestamp.tv_usec, cb.index, m[0], m[(size_t)bpl * 736], cp.bytesused == size);
        munmap(m, size);
        pf = (struct pollfd){ .fd = vfd, .events = 0x306 };
        if (poll(&pf, 1, 0) != 1) return 26;
        struct v4l2_plane op = { 0 };
        struct v4l2_buffer ob = { .type = types[0], .memory = V4L2_MEMORY_MMAP, .length = 1, .m.planes = &op };
        if (call(VIDIOC_DQBUF, &ob) || ob.index != (unsigned)slot) return 27;
        struct v4l2_plane rp = { .m.fd = out[cb.index] };
        struct v4l2_buffer rb = { .index = cb.index, .type = types[1], .memory = V4L2_MEMORY_DMABUF, .length = 1, .m.planes = &rp };
        if (call(VIDIOC_QBUF, &rb)) return 28;
    }
    if (call(VIDIOC_STREAMOFF, &types[1])) return 29;
    f = (struct v4l2_format){ .type = V4L2_BUF_TYPE_VIDEO_CAPTURE_MPLANE };
    f.fmt.pix_mp.width = 1920; f.fmt.pix_mp.height = 1088; f.fmt.pix_mp.pixelformat = V4L2_PIX_FMT_NV12;
    if (call(VIDIOC_S_FMT, &f)) return 30;
    size = f.fmt.pix_mp.plane_fmt[0].sizeimage;
    r = (struct v4l2_requestbuffers){ .count = 1, .type = types[1], .memory = V4L2_MEMORY_DMABUF };
    if (call(VIDIOC_REQBUFS, &r)) return 31;
    struct create_dumb big = { .height = 1088, .width = 1920, .bpp = 16 };
    if (drm_ioctl(card, 0xc02064b2, &big)) return 32;
    struct prime_handle bh = { .handle = big.handle, .flags = O_CLOEXEC };
    if (drm_ioctl(card, 0xc00c642d, &bh)) return 33;
    if (call(VIDIOC_STREAMON, &types[1])) return 34;
    struct v4l2_plane bp = { .m.fd = bh.fd };
    struct v4l2_buffer bb = { .index = 0, .type = types[1], .memory = V4L2_MEMORY_DMABUF, .length = 1, .m.planes = &bp };
    if (call(VIDIOC_QBUF, &bb)) return 35;
    memset(in[2], 'z', 50);
    struct v4l2_plane zp = { .bytesused = 50 };
    struct v4l2_buffer zb = { .index = 2, .type = types[0], .memory = V4L2_MEMORY_MMAP, .length = 1, .m.planes = &zp };
    zb.timestamp.tv_usec = 6;
    if (call(VIDIOC_QBUF, &zb)) return 36;
    struct v4l2_plane dp = { 0 };
    struct v4l2_buffer db = { .type = types[1], .memory = V4L2_MEMORY_DMABUF, .length = 1, .m.planes = &dp };
    if (call(VIDIOC_DQBUF, &db)) return 37;
    unsigned char *m = mmap(NULL, size, PROT_READ, MAP_SHARED, bh.fd, 0);
    printf("%ld %u %c%c\n", (long)db.timestamp.tv_usec, db.index, m[0], m[(size_t)1920 * 1088]);
    bl_vdec_closed(vfd);
    return 0;
}
'''

CONFIG = struct.Struct('<IIIIIIII')
CAPTURE = struct.Struct('<III')
PACKET = struct.Struct('<IIII q')
RESULT = struct.Struct('<IiI')


class FakeDecoder(threading.Thread):
    def __init__(self, path):
        super().__init__(daemon=True)
        self.listener = socket.socket(socket.AF_UNIX, socket.SOCK_SEQPACKET)
        self.listener.bind(path)
        self.listener.listen(1)
        self.configs, self.packets = [], []

    def run(self):
        while True:
            try:
                conn, _ = self.listener.accept()
            except OSError:
                return
            threading.Thread(target=self.serve, args=(conn,), daemon=True).start()

    def serve(self, conn):
        bitstream, captures, config = None, {}, None
        while True:
            fds = array.array('i')
            msg, ancdata, _, _ = conn.recvmsg(256, socket.CMSG_LEN(4 * fds.itemsize))
            if not msg:
                return
            for level, kind, data in ancdata:
                if level == socket.SOL_SOCKET and kind == socket.SCM_RIGHTS:
                    fds.frombytes(data[:len(data) - len(data) % fds.itemsize])
            kind = struct.unpack_from('<I', msg)[0]
            if kind == 6:
                config = CONFIG.unpack(msg)
                self.configs.append(config)
                bitstream = mmap.mmap(fds[0], config[6] * config[7], prot=mmap.PROT_READ)
                os.close(fds[0])
                conn.send(RESULT.pack(9, 0, 0))
            elif kind == 7:
                _, index, size = CAPTURE.unpack(msg)
                captures[index] = mmap.mmap(fds[0], size)
                os.close(fds[0])
            elif kind == 8:
                _, source, nbytes, target, pts = PACKET.unpack(msg)
                self.packets.append((source, nbytes, target, pts))
                first = bitstream[source * config[7]]
                frame = captures[target]
                frame[0] = first
                frame[config[4] * config[5]] = ord('u')
                conn.send(RESULT.pack(9, 0, 1))


class VdecEmulatorTest(unittest.TestCase):
    def test_remote_play_client_decodes_through_the_app_socket(self):
        with tempfile.TemporaryDirectory() as tmp:
            tmp = Path(tmp)
            driver = tmp / 'driver.c'
            driver.write_text(DRIVER)
            binary = tmp / 'streaming_client'
            subprocess.run(['cc', '-O1', '-pthread', '-DBL_VDEC_ANY_ARCH', '-o', str(binary), str(driver), str(VDEC),
                            str(VENC), '-ldl'], check=True)
            sock = tmp / 'venc.sock'
            server = FakeDecoder(str(sock))
            self.addCleanup(server.listener.close)
            server.start()
            done = subprocess.run([str(binary)], env=dict(os.environ, BL_VENC_SOCKET=str(sock)),
                                  capture_output=True, text=True, timeout=20)
            self.assertEqual(done.returncode, 0, done.stdout + done.stderr)
            self.assertEqual(done.stdout.split('\n')[:6],
                             ['1 0 bu 1', '2 1 cu 1', '3 2 du 1', '4 0 eu 1', '5 1 fu 1', '6 0 zu'])
            self.assertEqual([c[1:6] for c in server.configs],
                             [(1, 1280, 720, 1280, 736), (1, 1280, 720, 1920, 1088)])
            self.assertEqual([p[:3] for p in server.packets],
                             [(1, 101, 0), (2, 102, 1), (3, 103, 2), (0, 104, 0), (1, 105, 1), (2, 50, 0)])

    def test_other_programs_keep_the_real_libdrm(self):
        with tempfile.TemporaryDirectory() as tmp:
            tmp = Path(tmp)
            (tmp / 'main.c').write_text(
                'void *SDL_LoadObject(const char *);\n'
                'int main(void) { return SDL_LoadObject("libdrm.so.2") != 0; }\n')
            binary = tmp / 'steam'
            subprocess.run(['cc', '-O1', '-pthread', '-DBL_VDEC_ANY_ARCH', '-o', str(binary), str(tmp / 'main.c'),
                            str(VDEC), str(VENC), '-ldl'], check=True)
            done = subprocess.run([str(binary)], capture_output=True, text=True, timeout=20)
            self.assertEqual(done.returncode, 0, done.stderr)


if __name__ == '__main__':
    unittest.main()
