import subprocess
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
VDEC = ROOT / 'tools/linuxfs/preload/vdec.c'


class Bits:
    def __init__(self):
        self.bits = []

    def u(self, value, count):
        self.bits += [(value >> (count - 1 - i)) & 1 for i in range(count)]

    def ue(self, value):
        value += 1
        length = value.bit_length()
        self.u(0, length - 1)
        self.u(value, length)

    def bytes(self):
        bits = self.bits + [1]
        while len(bits) % 8:
            bits.append(0)
        raw = bytes(int(''.join(map(str, bits[i:i + 8])), 2) for i in range(0, len(bits), 8))
        out = bytearray()
        for i, byte in enumerate(raw):
            if len(out) >= 2 and out[-1] == 0 and out[-2] == 0 and byte <= 3:
                out.append(3)
            out.append(byte)
        return bytes(out)


def sps(width_mbs, height_units, profile=100, frame_mbs_only=1, poc_type=2):
    b = Bits()
    b.u(profile, 8)
    b.u(0, 8)
    b.u(51, 8)
    b.ue(0)
    if profile in (100, 110, 122, 244):
        b.ue(1)
        b.ue(0)
        b.ue(0)
        b.u(0, 1)
        b.u(0, 1)
    b.ue(0)
    b.ue(poc_type)
    if poc_type == 0:
        b.ue(4)
    b.ue(1)
    b.u(0, 1)
    b.ue(width_mbs - 1)
    b.ue(height_units - 1)
    b.u(frame_mbs_only, 1)
    if not frame_mbs_only:
        b.u(0, 1)
    b.u(1, 1)
    b.u(0, 1)
    return b'\x00\x00\x00\x01\x67' + b.bytes() + b'\x00\x00\x00\x01\x65\x88\x84\x00'


def hevc_sps(width, height, sub_layers_minus1=0):
    b = Bits()
    b.u(0, 4)
    b.u(sub_layers_minus1, 3)
    b.u(1, 1)
    b.u(0, 88)
    b.u(93, 8)
    for _ in range(sub_layers_minus1):
        b.u(0, 2)
    if sub_layers_minus1:
        for _ in range(sub_layers_minus1, 8):
            b.u(0, 2)
    b.ue(0)
    b.ue(1)
    b.ue(width)
    b.ue(height)
    b.u(0, 1)
    return b'\x00\x00\x00\x01\x42\x01' + b.bytes() + b'\x00\x00\x00\x01\x26\x01\xaf'


DRIVER = r'''
#include "vdec.c"
#include <stdio.h>

int main(int argc, char **argv) {
    FILE *f = fopen(argv[1], "rb");
    static uint8_t data[4096];
    size_t n = fread(data, 1, sizeof data, f);
    uint32_t w = 0, h = 0;
    int rc = argc > 2 ? vd_stream_size_h265(data, n, &w, &h) : vd_stream_size_h264(data, n, &w, &h);
    printf("%d %u %u\n", rc, w, h);
    return 0;
}
'''


class StreamSizeTest(unittest.TestCase):
    def run_driver(self, data, hevc=False):
        with tempfile.TemporaryDirectory() as tmp:
            tmp = Path(tmp)
            (tmp / 'driver.c').write_text(DRIVER)
            (tmp / 'stream.bin').write_bytes(data)
            exe = tmp / 'driver'
            subprocess.run(['gcc', '-std=gnu11', '-DDD_VDEC_ANY_ARCH', '-I', str(VDEC.parent), '-o', str(exe), str(tmp / 'driver.c'), str(VDEC.parent / 'venc.c'), '-ldl', '-lpthread'],
                           check=True, capture_output=True, text=True)
            return subprocess.run([str(exe), str(tmp / 'stream.bin')] + (['h265'] if hevc else []), check=True, capture_output=True, text=True).stdout.split()

    def test_reads_an_ultrawide_stream_size(self):
        self.assertEqual(['0', '3440', '1440'], self.run_driver(sps(215, 90)))

    def test_reads_a_small_stream_size(self):
        self.assertEqual(['0', '800', '464'], self.run_driver(sps(50, 29)))

    def test_interlaced_streams_double_the_height(self):
        self.assertEqual(['0', '1920', '1088'], self.run_driver(sps(120, 34, frame_mbs_only=0)))

    def test_baseline_profile_has_no_chroma_fields(self):
        self.assertEqual(['0', '1280', '720'], self.run_driver(sps(80, 45, profile=66)))

    def test_emulation_prevention_bytes_are_removed(self):
        self.assertEqual(['0', '256', '256'], self.run_driver(sps(16, 16, profile=66)))

    def test_reads_an_h265_stream_size(self):
        self.assertEqual(['0', '3440', '1440'], self.run_driver(hevc_sps(3440, 1440), hevc=True))

    def test_h265_sizes_round_up_to_sixteen(self):
        self.assertEqual(['0', '1920', '1088'], self.run_driver(hevc_sps(1920, 1080, sub_layers_minus1=2), hevc=True))

    def test_a_stream_without_an_sps_is_left_alone(self):
        self.assertEqual('-1', self.run_driver(b'\x00\x00\x00\x01\x65\x88\x84\x00\x00\x01')[0])


if __name__ == '__main__':
    unittest.main()
