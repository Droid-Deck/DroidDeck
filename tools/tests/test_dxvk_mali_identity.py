import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[2]
PATCH = ROOT / "tools/dxvk/mali-patches/0001-experimental-mali-capabilities-and-guards.patch"


class DxvkMaliIdentityTest(unittest.TestCase):
    def test_reported_devices_match_without_broadening_other_identity_guards(self):
        added = "\n".join(line[1:] for line in PATCH.read_text().splitlines()
                          if line.startswith("+") and not line.startswith("+++"))
        start = added.index("    bool matchesDevice =")
        end = added.index(";", added.index("    m_maliCompat =", start)) + 1
        predicate = added[start:end]
        compiler = shutil.which(os.environ.get("CXX", "c++"))
        self.assertIsNotNone(compiler, "a C++ compiler is required")
        source = r'''
#include <cstdint>
#include <cstring>
#include <iostream>
#include <string>

static std::string optIn;
namespace env {
    std::string getEnvVar(const char*) { return optIn; }
}
struct Properties {
    uint32_t deviceID;
    uint32_t vendorID;
    uint32_t driverVersion;
    const char* deviceName;
};
static bool enabled(const Properties& properties) {
    bool m_maliCompat = false;
''' + predicate + r'''
    return m_maliCompat;
}
int main() {
    struct Case { Properties properties; const char* optIn; bool expected; };
    const Case cases[] = {
        {{0xb8a20000, 0x13b5, 0x0d803000, "Mali-G715"}, "1", true},
        {{0xa8620000, 0x13b5, 0x0d803000, "Mali-G710"}, "1", true},
        {{0, 0x13b5, 0x0d803000, "Mali-G715"}, "1", true},
        {{0, 0x13b5, 0x0d803000, "Mali-G710"}, "1", true},
        {{0, 0x13b5, 0x0d803000, "Mali-G710 MC10"}, "1", false},
        {{0, 0x13b5, 0x0d803000, "Mali-G720"}, "1", false},
        {{0, 0x13b5, 0x0d803000, "Mali-G71"}, "1", false},
        {{0xdeadbeef, 0x13b5, 0x0d803000, "Mali-G710"}, "1", false},
        {{0xa8620000, 0x13b5, 0x0d803000, "Mali-G710"}, "", false},
        {{0xa8620000, 0x13b5, 0x0d803000, "Mali-G710"}, "0", false},
        {{0xa8620000, 0x13b5, 0x0d803000, "Mali-G710"}, "true", false},
        {{0xa8620000, 0x5143, 0x0d803000, "Mali-G710"}, "1", false},
        {{0, 0x5143, 0x0d803000, "Mali-G710"}, "1", false},
        {{0xa8620000, 0x13b5, 0x0d804000, "Mali-G710"}, "1", false},
        {{0, 0x13b5, 0x0d804000, "Mali-G710"}, "1", false},
    };
    for (unsigned i = 0; i < sizeof(cases) / sizeof(cases[0]); i++) {
        optIn = cases[i].optIn;
        if (enabled(cases[i].properties) != cases[i].expected) {
            std::cerr << "identity case " << i << " failed\n";
            return 1;
        }
    }
    std::cout << "15 identity cases passed\n";
}
'''
        with tempfile.TemporaryDirectory(prefix="droiddeck-mali-identity-") as directory:
            root = Path(directory)
            fixture = root / "identity.cpp"
            program = root / "identity-test"
            fixture.write_text(source)
            built = subprocess.run([compiler, "-std=c++17", "-Wall", "-Wextra", "-Werror",
                                    str(fixture), "-o", str(program)],
                                   capture_output=True, text=True)
            self.assertEqual(built.returncode, 0, built.stdout + built.stderr)
            ran = subprocess.run([str(program)], capture_output=True, text=True, timeout=30)
            self.assertEqual(ran.returncode, 0, ran.stdout + ran.stderr)
            self.assertIn("15 identity cases passed", ran.stdout)


if __name__ == "__main__":
    unittest.main()
