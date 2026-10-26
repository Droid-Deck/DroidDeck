import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]

STUBS = r"""
int bl_ntsync_ioctl(int fd, unsigned long req, void *arg, int *rc) { return 0; }
int bl_drm_gem_close(int fd, unsigned long req, void *arg, int *rc) { return 0; }
int bl_venc_ioctl(int fd, unsigned long req, void *arg, int *rc) { return 0; }
int bl_kgsl_poll(void *real, int fd, unsigned long req, void *arg, int *rc) { return 0; }
int bl_winebus_rumble_ioctl(void *real, int fd, unsigned long req, void *arg, void *caller, int *rc) { return 0; }
int bl_writewatch_ioctl(void *real, int fd, unsigned long req, void *arg, int *rc) { return 0; }
void bl_kgsl_chip_id_fixup(unsigned long req, void *arg, int rc) {}
int bl_status_without_tracer(const char *path, int flags, const void *caller) { return -1; }
int quest_kgsl_ioctl(int (*real)(int, unsigned long, void *), int fd, unsigned long req, void *arg) { return real(fd, req, arg); }
"""

PROGRAM = r"""
#include <arpa/inet.h>
#include <ifaddrs.h>
#include <net/if.h>
#include <stdio.h>
int main(void) {
    struct ifaddrs *list;
    if (getifaddrs(&list) != 0) return 2;
    for (struct ifaddrs *it = list; it; it = it->ifa_next) {
        if (!it->ifa_addr || it->ifa_addr->sa_family != AF_INET) continue;
        char ip[INET_ADDRSTRLEN], broadcast[INET_ADDRSTRLEN] = "-";
        inet_ntop(AF_INET, &((struct sockaddr_in *)it->ifa_addr)->sin_addr, ip, sizeof(ip));
        if ((it->ifa_flags & IFF_BROADCAST) && it->ifa_broadaddr)
            inet_ntop(AF_INET, &((struct sockaddr_in *)it->ifa_broadaddr)->sin_addr, broadcast, sizeof(broadcast));
        printf("addr %s %s %s\n", it->ifa_name, ip, broadcast);
    }
    freeifaddrs(list);
    struct if_nameindex *names = if_nameindex();
    if (!names) return 3;
    for (struct if_nameindex *it = names; it->if_index; it++) printf("index %s %u\n", it->if_name, it->if_index);
    if_freenameindex(names);
    printf("selected-index %u\n", if_nametoindex("lan-test"));
    return 0;
}
"""


@unittest.skipUnless(sys.platform == "linux" and shutil.which("cc"), "needs Linux and a C compiler")
class LanNetifPreloadTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory()
        cls.directory = Path(cls.temp.name)
        cls.link = cls.directory / "network"
        stub = cls.directory / "stubs.c"
        stub.write_text(STUBS)
        program = cls.directory / "program.c"
        program.write_text(PROGRAM)
        cls.preload = cls.directory / "libnetif.so"
        cls.program = cls.directory / "program"
        subprocess.run(["cc", "-shared", "-fPIC", "-O2", "-Wall", "-pthread", f'-DLINK_FILE="{cls.link}"',
                        "-o", str(cls.preload), str(ROOT / "tools/linuxfs/preload/netif.c"), str(stub), "-ldl"], check=True)
        subprocess.run(["cc", "-Wall", "-Werror", "-o", str(cls.program), str(program)], check=True)

    @classmethod
    def tearDownClass(cls):
        cls.temp.cleanup()

    def run_program(self, selected):
        self.link.write_text("if lan-test 23 1400\nmac 02:12:34:56:78:90\naddr 192.168.73.1 24\n" + selected)
        return subprocess.check_output([str(self.program)], env=dict(os.environ, LD_PRELOAD=str(self.preload)), text=True, timeout=10)

    def test_selected_interface_overrides_readable_system_interfaces_and_preserves_broadcast(self):
        output = self.run_program("selected 1\n")
        self.assertIn("addr lan-test 192.168.73.1 192.168.73.255\n", output)
        self.assertIn("index lan-test 23\n", output)
        self.assertIn("selected-index 23\n", output)
        self.assertEqual(2, sum(line.startswith("addr ") for line in output.splitlines()))
        self.assertEqual(2, sum(line.startswith("index ") for line in output.splitlines()))

    def test_default_mode_keeps_real_interfaces_when_readable(self):
        baseline = subprocess.check_output([str(self.program)], text=True, timeout=10)
        if not any(line.startswith("addr ") and not line.startswith("addr lo ") for line in baseline.splitlines()):
            self.skipTest("needs a non-loopback IPv4 interface")
        output = self.run_program("")
        self.assertNotIn("addr lan-test", output)
        self.assertNotIn("index lan-test", output)


if __name__ == "__main__":
    unittest.main()
