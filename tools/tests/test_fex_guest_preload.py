from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import unittest

PATCH = Path(__file__).resolve().parents[1] / "fex/patches/0014-guest-preload-drops-host-libraries.patch"

HARNESS = r"""
#include <cstdio>
#include <cstring>
#include <elf.h>
#include <fcntl.h>
#include <string>
#include <unistd.h>
namespace fextl { using string = std::string; }
namespace ELFLoader {
struct ELFContainer {
  enum ELFType { TYPE_NONE, TYPE_X86_64, TYPE_X86_32, TYPE_OTHER_ELF };
  static ELFType GetELFType(const std::string& Filename) {
    int FD = open(Filename.c_str(), O_RDONLY | O_CLOEXEC);
    if (FD == -1) return TYPE_NONE;
    Elf64_Ehdr Header {};
    ssize_t Read = pread(FD, &Header, sizeof(Header), 0);
    close(FD);
    if (Read < (ssize_t)sizeof(Header) || memcmp(Header.e_ident, ELFMAG, SELFMAG) != 0) return TYPE_NONE;
    if (Header.e_machine == EM_X86_64) return TYPE_X86_64;
    if (Header.e_machine == EM_386) return TYPE_X86_32;
    return TYPE_OTHER_ELF;
  }
};
}
struct Loader {
  static fextl::string ResolveRootfsFile(const fextl::string& File, fextl::string RootFS) { return RootFS + File; }
  GUEST_PRELOAD
};
int main(int argc, char** argv) {
  std::printf("%s", Loader::GuestPreload(argv[1], argv[2]).c_str());
}
"""


def patch_function():
    lines = [line[1:] for line in PATCH.read_text().splitlines() if line.startswith("+") and not line.startswith("+++")]
    body = "\n".join(lines)
    first = body.index("  static fextl::string GuestPreload(")
    last = body.index("\n  }\n", first) + len("\n  }\n")
    return body[first:last]


@unittest.skipUnless(shutil.which("g++") and shutil.which("gcc") and shutil.which("aarch64-linux-gnu-gcc"), "needs g++, gcc and aarch64-linux-gnu-gcc")
class FexGuestPreloadTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.TemporaryDirectory()
        cls.dir = Path(cls.tmp.name)
        source = cls.dir / "harness.cpp"
        source.write_text(HARNESS.replace("GUEST_PRELOAD", patch_function()))
        cls.binary = cls.dir / "harness"
        subprocess.run(["g++", "-std=c++20", "-O1", "-o", str(cls.binary), str(source)], check=True)
        stub = cls.dir / "stub.c"
        stub.write_text("int stub(void) { return 0; }\n")
        cls.host = cls.dir / "host"
        cls.rootfs = cls.dir / "rootfs"
        cls.shadow = cls.host / "lib/libshadow.so"
        cls.rootfs_shadow = Path(str(cls.rootfs) + str(cls.shadow))
        for folder in (cls.host / "lib", cls.rootfs_shadow.parent):
            folder.mkdir(parents=True)
        cls.compile("aarch64-linux-gnu-gcc", stub, cls.host / "lib/libhost.so")
        cls.compile("gcc", stub, cls.host / "lib/libguest.so")
        cls.compile("aarch64-linux-gnu-gcc", stub, cls.shadow)
        cls.compile("gcc", stub, cls.rootfs_shadow)

    @classmethod
    def tearDownClass(cls):
        cls.tmp.cleanup()

    @staticmethod
    def compile(compiler, source, output):
        subprocess.run([compiler, "-shared", "-fPIC", "-o", str(output), str(source)], check=True)

    def preload(self, value, rootfs=None):
        root = str(self.rootfs) if rootfs is None else rootfs
        return subprocess.run([str(self.binary), value, root], check=True, capture_output=True, text=True).stdout

    def test_host_libraries_leave_the_guest_list(self):
        host, guest = self.host / "lib/libhost.so", self.host / "lib/libguest.so"
        self.assertEqual(str(guest), self.preload(f"{host}:{guest}"))
        self.assertEqual("", self.preload(str(host)))

    def test_rootfs_copy_decides_before_the_host_path(self):
        self.assertEqual(str(self.shadow), self.preload(str(self.shadow)))
        self.assertEqual("", self.preload(str(self.shadow), ""))

    def test_names_missing_files_and_separators_are_kept(self):
        missing = str(self.dir / "missing.so")
        guest = str(self.host / "lib/libguest.so")
        self.assertEqual(f"libgamemodeauto.so.0:{missing}:{guest}",
                         self.preload(f" libgamemodeauto.so.0::{missing} {guest}:"))

    def test_patch_adds_no_comments(self):
        added = [line[1:].strip() for line in PATCH.read_text().splitlines() if line.startswith("+") and not line.startswith("+++")]
        self.assertFalse([line for line in added if line.startswith(("//", "/*"))])
        self.assertEqual(["Source/Tools/FEXInterpreter/ELFCodeLoader.h"], re.findall(r"^diff --git a/(\S+) ", PATCH.read_text(), re.M))


if __name__ == "__main__":
    unittest.main()
