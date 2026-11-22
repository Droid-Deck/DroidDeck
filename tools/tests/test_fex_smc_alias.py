import re
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

PATCH = Path(__file__).resolve().parents[1] / "fex/patches/0006-smc-alias-of-code-created-after-translation.patch"

HARNESS = r"""
#include <algorithm>
#include <cstdint>
#include <cstdio>
#include <map>
#include <string>
#include <sys/mman.h>

namespace FEXCore::Config {
enum { CONFIG_SMC_NONE, CONFIG_SMC_MTRACK, CONFIG_SMC_FULL };
}
namespace LogMan::Throw {
template<typename... Args> void AFmt(bool, const char*, Args...) {}
}

union VMAProt {
  struct { bool Readable : 1; bool Writable : 1; bool Executable : 1; };
};
struct VMAFlags { bool Shared : 1; };
struct MappedResource;
struct VMAEntry {
  MappedResource* Resource;
  VMAEntry* ResourcePrevVMA;
  VMAEntry* ResourceNextVMA;
  uint64_t Base, Offset, Length;
  VMAFlags Flags;
  VMAProt Prot;
};
struct MappedResource { VMAEntry* FirstVMA; };
struct VMATrackingT {
  std::map<uint64_t, VMAEntry> VMAs;
  std::map<uint64_t, VMAEntry>::const_iterator FindVMAEntry(uint64_t Addr) const {
    auto it = VMAs.upper_bound(Addr);
    if (it == VMAs.begin()) return VMAs.end();
    --it;
    return Addr < it->first + it->second.Length ? it : VMAs.end();
  }
};

struct Recorded { uint64_t Address, Length; int Prot; };
static Recorded calls[16];
static int count;
static int fake_mprotect(void* address, size_t length, int prot) {
  calls[count++] = {(uint64_t)address, length, prot};
  return 0;
}
#define mprotect fake_mprotect

struct SyscallHandler {
  int SMCChecks = FEXCore::Config::CONFIG_SMC_MTRACK;
  VMATrackingT VMATracking;
  void ProtectWritableAliasOfCode(uint64_t Base, uint64_t Length);
};

#include "extracted.inc"

static MappedResource resource, other;

int main(int argc, char** argv) {
  std::string scenario = argc > 1 ? argv[1] : "";
  SyscallHandler handler;
  VMAEntry code{}, alias{};
  code.Resource = &resource;
  code.Base = 0x100000;
  code.Offset = 0x10000;
  code.Length = 0x4000;
  code.Flags.Shared = true;
  code.Prot.Readable = true;
  code.Prot.Executable = true;
  alias.Resource = &resource;
  alias.Base = 0x700000;
  alias.Offset = 0x10000;
  alias.Length = 0x4000;
  alias.Flags.Shared = true;
  alias.Prot.Readable = true;
  alias.Prot.Writable = true;
  uint64_t base = alias.Base, length = alias.Length;
  if (scenario == "disjoint") {
    code.Offset = 0x80000;
  } else if (scenario == "partial") {
    code.Offset = 0x12000;
    code.Length = 0x1000;
  } else if (scenario == "tail") {
    base += 0x2000;
    length = 0x10000;
  } else if (scenario == "gap") {
    base -= 0x2000;
    length = 0x6000;
  } else if (scenario == "two") {
    length = 0x6000;
  } else if (scenario == "no_code") {
    code.Prot.Executable = false;
  } else if (scenario == "private") {
    alias.Flags.Shared = false;
  } else if (scenario == "read_only") {
    alias.Prot.Writable = false;
  } else if (scenario == "full") {
    handler.SMCChecks = FEXCore::Config::CONFIG_SMC_FULL;
  } else if (scenario == "none") {
    handler.SMCChecks = FEXCore::Config::CONFIG_SMC_NONE;
  }
  code.ResourceNextVMA = &alias;
  alias.ResourcePrevVMA = &code;
  resource.FirstVMA = &code;
  handler.VMATracking.VMAs[code.Base] = code;
  handler.VMATracking.VMAs[alias.Base] = alias;
  handler.VMATracking.VMAs[code.Base].ResourceNextVMA = &handler.VMATracking.VMAs[alias.Base];
  handler.VMATracking.VMAs[alias.Base].ResourcePrevVMA = &handler.VMATracking.VMAs[code.Base];
  resource.FirstVMA = &handler.VMATracking.VMAs[code.Base];
  handler.VMATracking.VMAs[code.Base].Resource = &resource;
  handler.VMATracking.VMAs[alias.Base].Resource = &resource;
  if (scenario == "two") {
    VMAEntry& next_code = handler.VMATracking.VMAs[0x200000];
    VMAEntry& next_alias = handler.VMATracking.VMAs[0x704000];
    next_code = {};
    next_code.Resource = &other;
    next_code.Base = 0x200000;
    next_code.Length = 0x2000;
    next_code.Flags.Shared = true;
    next_code.Prot.Readable = true;
    next_code.Prot.Executable = true;
    next_alias = {};
    next_alias.Resource = &other;
    next_alias.Base = 0x704000;
    next_alias.Length = 0x2000;
    next_alias.Flags.Shared = true;
    next_alias.Prot.Readable = true;
    next_alias.Prot.Writable = true;
    next_code.ResourceNextVMA = &next_alias;
    next_alias.ResourcePrevVMA = &next_code;
    other.FirstVMA = &next_code;
  }
  handler.ProtectWritableAliasOfCode(base, length);
  printf("%d", count);
  for (int i = 0; i < count; i++) printf(" %llx+%llx:%d", (unsigned long long)calls[i].Address, (unsigned long long)calls[i].Length, calls[i].Prot);
  printf("\n");
  return 0;
}
"""


def extracted():
    text = PATCH.read_text()
    section = re.search(r"^diff --git a/\S*SyscallsSMCTracking\.cpp .*?(?=^diff --git |\Z)", text, re.S | re.M).group(0)
    added = [line[1:] for line in section.splitlines() if line.startswith("+") and not line.startswith("+++")]
    body = "\n".join(added)
    start = body.index("void SyscallHandler::ProtectWritableAliasOfCode")
    end = body.index("\n}\n", start) + 3
    return body[start:end]


class SmcAliasTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        compiler = shutil.which("g++") or shutil.which("c++")
        if not compiler:
            raise unittest.SkipTest("no C++ compiler")
        cls.tmp = Path(tempfile.mkdtemp(prefix="fex-smc-alias-"))
        (cls.tmp / "extracted.inc").write_text(extracted())
        (cls.tmp / "harness.cpp").write_text(HARNESS)
        subprocess.run([compiler, "-std=c++20", "-fno-strict-aliasing", "-I", str(cls.tmp), "-o", str(cls.tmp / "harness"), str(cls.tmp / "harness.cpp")],
                       check=True, capture_output=True, text=True)

    @classmethod
    def tearDownClass(cls):
        shutil.rmtree(cls.tmp, ignore_errors=True)

    def run_scenario(self, name=""):
        return subprocess.run([str(self.tmp / "harness"), name], check=True, capture_output=True, text=True).stdout.strip()

    def test_writable_alias_of_translated_code_is_read_only_until_it_faults(self):
        self.assertEqual(self.run_scenario(), "1 700000+4000:1")

    def test_only_the_overlapping_part_of_the_alias_is_protected(self):
        self.assertEqual(self.run_scenario("partial"), "1 702000+1000:1")

    def test_alias_beyond_the_mapping_end_is_clamped(self):
        self.assertEqual(self.run_scenario("tail"), "1 702000+2000:1")

    def test_range_starting_in_a_gap_reaches_the_alias_after_it(self):
        self.assertEqual(self.run_scenario("gap"), "1 700000+4000:1")

    def test_every_alias_in_one_range_is_protected(self):
        self.assertEqual(self.run_scenario("two"), "2 700000+4000:1 704000+2000:1")

    def test_alias_of_another_file_range_is_untouched(self):
        self.assertEqual(self.run_scenario("disjoint"), "0")

    def test_alias_without_an_executable_mirror_is_untouched(self):
        self.assertEqual(self.run_scenario("no_code"), "0")

    def test_private_and_read_only_mappings_are_untouched(self):
        self.assertEqual(self.run_scenario("private"), "0")
        self.assertEqual(self.run_scenario("read_only"), "0")

    def test_explicit_smc_check_settings_are_respected(self):
        self.assertEqual(self.run_scenario("full"), "0")
        self.assertEqual(self.run_scenario("none"), "0")


if __name__ == "__main__":
    unittest.main()
