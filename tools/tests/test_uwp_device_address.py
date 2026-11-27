"""The device address a title publishes must name this device.

The snapshot the shim starts from is a real Windows 10 one, which is what makes
its record layout right, but the addresses inside it belonged to whichever machine
produced it. Only the last two IPv4 records were ever patched with the detected
address, so the earlier records kept 100.104.199.117 (carrier-grade NAT, which is
not RFC 1918 and therefore reads as a routable public address) and 172.28.144.1.
A peer following the same "prefer the public address" rule picked the stranger's
address, and the room stayed empty.

This compiles the shipping xbl_sda_rewrite_addresses() out of uwp-services.c and
runs it, so the check follows the real code rather than a copy of its logic.
"""

import re
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / "tools/xbox/uwp-host/uwp-services.c"

# A Windows 10 snapshot as the shim ships it: version 1, a 13-byte host id, then
# a Teredo and a ULA IPv6 and four IPv4 records, none of which is this device.
TEMPLATE = bytes([
    0x01, 0x00, 0x0e, 0x00, 0x00, 0x00, 0x00, 0x00,
    0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
    0x04, 0x06, 0x02, 0x20, 0x01, 0x00, 0x00, 0x14,
    0xc9, 0xc8, 0x4c, 0x2c, 0xf4, 0x3a, 0x96, 0x3f,
    0x57, 0xcd, 0x1f, 0x02, 0xfd, 0x7a, 0x11, 0x5c,
    0xa1, 0xe0, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
    0xf3, 0x3a, 0xc7, 0x75, 0x01, 0x64, 0x68, 0xc7,
    0x75, 0x01, 0xac, 0x1c, 0x90, 0x01, 0x01, 0xc0,
    0xa8, 0x01, 0x64, 0x01, 0x0a, 0x00, 0x02, 0x0f,
])

PREAMBLE = """
#include <stdio.h>
#include <string.h>
#include <stdint.h>
#include <stdarg.h>

typedef uint32_t UINT32;
typedef long LONG;
typedef int BOOL;
#define TRUE 1
#define FALSE 0
/* InterlockedCompareExchange(dest, exchange, comparand) -> the initial value. */
#define InterlockedCompareExchange(p, a, b) __sync_val_compare_and_swap((p), (b), (a))

typedef struct {
    unsigned char snapshot[1024];
    UINT32 snapshot_len;
    BOOL is_valid;
    BOOL is_local;
} xbl_devaddr_object;

static xbl_devaddr_object xbl_devaddr_local;
static unsigned char g_xbl_local_ipv4[4];
static unsigned char g_xbl_local_ipv6[16];
static int g_xbl_local_ipv6_ok;

static void svc_tracef(const char *fmt, ...)
{
    va_list ap;
    va_start(ap, fmt);
    vprintf(fmt, ap);
    va_end(ap);
    printf("\\n");
}
"""

DRIVER = """
static const unsigned char tmpl[72] = {
    0x01, 0x00, 0x0e, 0x00, 0x00, 0x00, 0x00, 0x00,
    0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
    0x04, 0x06, 0x02, 0x20, 0x01, 0x00, 0x00, 0x14,
    0xc9, 0xc8, 0x4c, 0x2c, 0xf4, 0x3a, 0x96, 0x3f,
    0x57, 0xcd, 0x1f, 0x02, 0xfd, 0x7a, 0x11, 0x5c,
    0xa1, 0xe0, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
    0xf3, 0x3a, 0xc7, 0x75, 0x01, 0x64, 0x68, 0xc7,
    0x75, 0x01, 0xac, 0x1c, 0x90, 0x01, 0x01, 0xc0,
    0xa8, 0x01, 0x64, 0x01, 0x0a, 0x00, 0x02, 0x0f
};

static void report(const char *label)
{
    const unsigned char *d = xbl_devaddr_local.snapshot;
    int pos = 18, i;

    printf("CASE %s\\n", label);
    printf("LEN %u\\n", (unsigned)xbl_devaddr_local.snapshot_len);
    printf("HDR ");
    for (i = 0; i < 18; i++) printf("%02x", d[i]);
    printf("\\n");
    for (i = 0; i < d[17] && pos < 72; i++) {
        int tag = d[pos++];
        if (tag == 0x01) {
            printf("REC ipv4 %u.%u.%u.%u\\n", d[pos], d[pos+1], d[pos+2], d[pos+3]);
            pos += 4;
        } else if (tag == 0x02) {
            printf("REC ipv6 %02x%02x:%02x%02x:%02x%02x:%02x%02x:"
                   "%02x%02x:%02x%02x:%02x%02x:%02x%02x\\n",
                   d[pos],d[pos+1],d[pos+2],d[pos+3],d[pos+4],d[pos+5],d[pos+6],d[pos+7],
                   d[pos+8],d[pos+9],d[pos+10],d[pos+11],d[pos+12],d[pos+13],d[pos+14],d[pos+15]);
            pos += 16;
        } else {
            printf("REC unknown %02x\\n", tag);
            break;
        }
    }
}

int main(void)
{
    memcpy(xbl_devaddr_local.snapshot, tmpl, 72);
    xbl_devaddr_local.snapshot_len = 72;
    xbl_devaddr_local.is_local = TRUE;
    g_xbl_local_ipv4[0] = 192; g_xbl_local_ipv4[1] = 168;
    g_xbl_local_ipv4[2] = 50;  g_xbl_local_ipv4[3] = 99;
    g_xbl_local_ipv6_ok = 0;
    xbl_sda_rewrite_addresses();
    report("v4only");

    /* Same device, now with a routable IPv6. */
    memcpy(xbl_devaddr_local.snapshot, tmpl, 72);
    g_xbl_local_ipv6_ok = 1;
    memset(g_xbl_local_ipv6, 0, 16);
    g_xbl_local_ipv6[0] = 0x20; g_xbl_local_ipv6[1] = 0x01;
    g_xbl_local_ipv6[7] = 0x42; g_xbl_local_ipv6[8] = 0xab; g_xbl_local_ipv6[15] = 0x7f;
    xbl_sda_rewrite_addresses();
    report("v6");
    return 0;
}
"""


def rewrite_function():
    """The definition of xbl_sda_rewrite_addresses, taken from the source file."""
    text = SOURCE.read_text(encoding="utf-8", errors="replace")
    match = re.search(r"static void xbl_sda_rewrite_addresses\s*\(\s*void\s*\)\s*\{", text)
    if not match:
        raise AssertionError(
            "xbl_sda_rewrite_addresses is gone from uwp-services.c; this test no longer covers it")
    depth, index = 0, match.end() - 1
    while index < len(text):
        if text[index] == "{":
            depth += 1
        elif text[index] == "}":
            depth -= 1
            if depth == 0:
                return text[match.start():index + 1]
        index += 1
    raise AssertionError("unbalanced braces after xbl_sda_rewrite_addresses")


def parse(output):
    cases, current = {}, None
    for line in output.splitlines():
        parts = line.split()
        if not parts:
            continue
        if parts[0] == "CASE":
            current = parts[1]
            cases[current] = {"records": [], "len": None, "hdr": None}
        elif current is None:
            continue
        elif parts[0] == "LEN":
            cases[current]["len"] = int(parts[1])
        elif parts[0] == "HDR":
            cases[current]["hdr"] = parts[1]
        elif parts[0] == "REC":
            cases[current]["records"].append((parts[1], " ".join(parts[2:])))
    return cases


class DeviceAddressTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cc = shutil.which("cc") or shutil.which("gcc")
        if not cc:
            raise unittest.SkipTest("no C compiler to build the device-address check")
        source = PREAMBLE + rewrite_function() + DRIVER
        cls.directory = tempfile.TemporaryDirectory()
        directory = Path(cls.directory.name)
        (directory / "check.c").write_text(source)
        exe = directory / "check"
        build = subprocess.run([cc, "-O1", "-o", str(exe), str(directory / "check.c")],
                               text=True, capture_output=True)
        if build.returncode != 0:
            raise AssertionError("could not build the device-address check:\n" + build.stderr)
        run = subprocess.run([str(exe)], text=True, capture_output=True)
        if run.returncode != 0:
            raise AssertionError("the device-address check did not run:\n" + run.stderr)
        cls.output = run.stdout
        cls.cases = parse(run.stdout)

    @classmethod
    def tearDownClass(cls):
        cls.directory.cleanup()

    def test_snapshot_keeps_its_shape(self):
        for name, case in self.cases.items():
            with self.subTest(case=name):
                self.assertEqual(72, case["len"])
                self.assertEqual(TEMPLATE[:18].hex(), case["hdr"])
                self.assertEqual(6, len(case["records"]))

    def test_no_foreign_address_survives(self):
        case = self.cases["v4only"]
        addresses = [value for _, value in case["records"]]
        for foreign in ("100.104.199.117", "172.28.144.1", "10.0.2.15"):
            self.assertNotIn(foreign, addresses)
        self.assertNotIn("2001:0000:14c9:c84c:2cf4:3a96:3f57:cd1f", addresses)
        self.assertNotIn("fd7a:115c:a1e0:0000:0000:0000:f33a:c775", addresses)

    def test_every_record_names_this_device(self):
        case = self.cases["v4only"]
        ipv4 = [value for kind, value in case["records"] if kind == "ipv4"]
        ipv6 = [value for kind, value in case["records"] if kind == "ipv6"]
        self.assertEqual(["192.168.50.99"] * 4, ipv4)
        # With no IPv6 on the host, the IPv4 address is stated in mapped form
        # rather than leaving an address that belongs to another machine.
        self.assertEqual(["0000:0000:0000:0000:0000:ffff:c0a8:3263"] * 2, ipv6)

    def test_global_ipv6_is_used_when_the_host_has_one(self):
        case = self.cases["v6"]
        ipv6 = [value for kind, value in case["records"] if kind == "ipv6"]
        self.assertEqual(["2001:0000:0000:0042:ab00:0000:0000:007f"] * 2, ipv6)
        ipv4 = [value for kind, value in case["records"] if kind == "ipv4"]
        self.assertEqual(["192.168.50.99"] * 4, ipv4)


if __name__ == "__main__":
    unittest.main()
