from pathlib import Path
import shutil
import struct
import subprocess
import tempfile
import unittest
import uuid

SOURCE = Path(__file__).resolve().parents[2] / "app/src/main/cpp/ntfs"

HARNESS = r"""
#define _FILE_OFFSET_BITS 64
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "parttab.h"

static FILE *image;
static unsigned block_size;

static int read_blocks(void *ctx, uint64_t lba, uint32_t count, void *buf) {
    (void)ctx;
    size_t want = (size_t)count * block_size;
    memset(buf, 0, want);
    if (fseeko(image, (off_t)(lba * block_size), SEEK_SET) != 0) return -1;
    size_t got = fread(buf, 1, want, image);
    return got <= want ? 0 : -1;
}

int main(int argc, char **argv) {
    if (argc != 4) return 2;
    image = fopen(argv[1], "rb");
    block_size = (unsigned)strtoul(argv[2], NULL, 10);
    unsigned long long blocks = strtoull(argv[3], NULL, 10);
    parttab_entry entries[PARTTAB_MAX];
    int count = parttab_scan(read_blocks, NULL, block_size, blocks, entries, PARTTAB_MAX);
    printf("%d\n", count);
    for (int i = 0; i < count; i++) {
        printf("%d %llu %llu %s|%s\n", entries[i].index, (unsigned long long)entries[i].start,
               (unsigned long long)entries[i].blocks, entries[i].fs[0] ? entries[i].fs : "-", entries[i].label);
    }
    return 0;
}
"""

BASIC_DATA = uuid.UUID("EBD0A0A2-B9E5-4433-87C0-68B6B72699C7")
RESERVED = uuid.UUID("E3C9E316-0B5C-4DB8-817D-F92DF00215AE")


def boot_sector(kind, size):
    sector = bytearray(size)
    if kind == "ntfs":
        sector[3:11] = b"NTFS    "
    elif kind == "exfat":
        sector[3:11] = b"EXFAT   "
    elif kind == "fat":
        sector[82:90] = b"FAT32   "
    sector[510:512] = b"\x55\xaa"
    return bytes(sector)


def mbr(entries, size=512):
    sector = bytearray(size)
    for slot, (kind, start, length) in enumerate(entries):
        struct.pack_into("<B3xB3xII", sector, 446 + 16 * slot, 0, kind, start, length)
    sector[510:512] = b"\x55\xaa"
    return bytes(sector)


class Disk:
    def __init__(self, path, block_size, blocks):
        self.path = path
        self.block_size = block_size
        self.blocks = blocks
        with open(path, "wb") as f:
            f.truncate(block_size * blocks)

    def write(self, lba, data):
        with open(self.path, "r+b") as f:
            f.seek(lba * self.block_size)
            f.write(data)

    def gpt(self, partitions):
        self.write(0, mbr([(0xEE, 1, min(self.blocks - 1, 0xFFFFFFFF))], self.block_size))
        header = bytearray(self.block_size)
        header[0:8] = b"EFI PART"
        struct.pack_into("<QII", header, 72, 2, 128, 128)
        self.write(1, bytes(header))
        table = bytearray(128 * 128)
        for slot, (type_guid, first, last, name) in enumerate(partitions):
            offset = 128 * slot
            table[offset:offset + 16] = type_guid.bytes_le
            table[offset + 16:offset + 32] = uuid.uuid4().bytes_le
            struct.pack_into("<QQ", table, offset + 32, first, last)
            encoded = name.encode("utf-16-le")[:72]
            table[offset + 56:offset + 56 + len(encoded)] = encoded
        self.write(2, bytes(table))


class PartitionTableTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.dir = Path(tempfile.mkdtemp())
        (cls.dir / "harness.c").write_text(HARNESS)
        cls.scan = cls.dir / "scan"
        subprocess.run(
            ["cc", "-O2", "-Wall", "-Wextra", "-I", str(SOURCE), "-o", str(cls.scan),
             str(cls.dir / "harness.c"), str(SOURCE / "parttab.c")],
            check=True,
        )

    @classmethod
    def tearDownClass(cls):
        shutil.rmtree(cls.dir, True)

    def disk(self, block_size=512, blocks=2 * 1024 * 1024):
        return Disk(self.dir / f"{self.id().rsplit('.', 1)[-1]}.img", block_size, blocks)

    def scan_disk(self, disk):
        out = subprocess.run(
            [str(self.scan), str(disk.path), str(disk.block_size), str(disk.blocks)],
            check=True, capture_output=True, encoding="utf-8",
        ).stdout.splitlines()
        count = int(out[0])
        rows = []
        for line in out[1:]:
            numbers, label = line.split("|", 1)
            index, start, length, fs = numbers.split(" ")
            rows.append((int(index), int(start), int(length), fs, label))
        self.assertEqual(count, len(rows))
        return rows

    def test_mbr_ntfs_partition(self):
        disk = self.disk()
        disk.write(0, mbr([(0x07, 2048, 1_000_000)]))
        disk.write(2048, boot_sector("ntfs", 512))
        self.assertEqual([(1, 2048, 1_000_000, "ntfs", "")], self.scan_disk(disk))

    def test_gpt_skips_microsoft_reserved_and_keeps_labels(self):
        disk = self.disk()
        disk.gpt([
            (RESERVED, 34, 32_801, "Microsoft reserved partition"),
            (BASIC_DATA, 32_802, 1_032_801, "Basic data partition"),
            (BASIC_DATA, 1_032_802, 2_000_000, "Gämes ✓"),
        ])
        disk.write(32_802, boot_sector("ntfs", 512))
        disk.write(1_032_802, boot_sector("exfat", 512))
        self.assertEqual(
            [(2, 32_802, 1_000_000, "ntfs", "Basic data partition"),
             (3, 1_032_802, 967_199, "exfat", "Gämes ✓")],
            self.scan_disk(disk),
        )

    def test_whole_disk_ntfs_without_a_table(self):
        disk = self.disk()
        disk.write(0, boot_sector("ntfs", 512))
        self.assertEqual([(0, 0, disk.blocks, "ntfs", "")], self.scan_disk(disk))

    def test_logical_partitions_inside_an_extended_partition(self):
        disk = self.disk()
        disk.write(0, mbr([(0x07, 2048, 100_000), (0x0F, 200_000, 1_000_000)]))
        disk.write(2048, boot_sector("ntfs", 512))
        disk.write(200_000, mbr([(0x07, 2048, 300_000), (0x05, 400_000, 500_000)]))
        disk.write(202_048, boot_sector("exfat", 512))
        disk.write(600_000, mbr([(0x07, 2048, 100_000)]))
        disk.write(602_048, boot_sector("ntfs", 512))
        self.assertEqual(
            [(1, 2048, 100_000, "ntfs", ""),
             (5, 202_048, 300_000, "exfat", ""),
             (6, 602_048, 100_000, "ntfs", "")],
            self.scan_disk(disk),
        )

    def test_extended_chain_that_links_backwards_stops(self):
        disk = self.disk()
        disk.write(0, mbr([(0x0F, 200_000, 1_000_000)]))
        disk.write(200_000, mbr([(0x07, 2048, 1000), (0x05, 100_000, 1000)]))
        disk.write(300_000, mbr([(0x07, 2048, 1000), (0x05, 50_000, 1000)]))
        disk.write(250_000, mbr([(0x07, 2048, 1000), (0x05, 100_000, 1000)]))
        self.assertEqual(
            [(5, 202_048, 1000, "-", ""), (6, 302_048, 1000, "-", "")],
            self.scan_disk(disk),
        )

    def test_4k_logical_blocks(self):
        disk = self.disk(block_size=4096, blocks=262_144)
        disk.gpt([(BASIC_DATA, 256, 200_255, "Data")])
        disk.write(256, boot_sector("ntfs", 4096))
        self.assertEqual([(1, 256, 200_000, "ntfs", "Data")], self.scan_disk(disk))

    def test_entries_outside_the_disk_are_ignored(self):
        disk = self.disk()
        disk.write(0, mbr([(0x07, 2048, 10_000), (0x07, disk.blocks - 10, 100), (0x07, disk.blocks + 5, 10)]))
        disk.write(2048, boot_sector("ntfs", 512))
        self.assertEqual([(1, 2048, 10_000, "ntfs", "")], self.scan_disk(disk))

    def test_blank_disk_has_no_partitions(self):
        self.assertEqual([], self.scan_disk(self.disk()))


if __name__ == "__main__":
    unittest.main()
