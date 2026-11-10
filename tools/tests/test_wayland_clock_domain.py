from pathlib import Path
import struct
import subprocess
import tempfile
import unittest

SOURCE = Path(__file__).resolve().parents[2] / "app/src/main/cpp/waylandcomp/src/clock_domain.c"
HEADER = SOURCE.with_suffix(".h")


class WaylandClockDomainTest(unittest.TestCase):
    def test_host_timestamp_uses_the_guest_domain(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            control = root / "clock"
            control.write_bytes(struct.pack("<q", 10_000_000_000))
            program = root / "program.c"
            program.write_text(
                '#include <stdio.h>\n'
                '#include "clock_domain.h"\n'
                'int main(int argc, char **argv) { (void)argc;\n'
                '  droiddeck_clock_domain_set_control(argv[1]);\n'
                '  printf("%lld %u %lld\\n", (long long)droiddeck_clock_domain_adjust_ns(30LL * 1000000000LL), '
                '         droiddeck_clock_domain_now_ms(), (long long)droiddeck_clock_domain_adjust_ns(5LL * 1000000000LL));\n'
                '  return 0;\n'
                '}\n'
            )
            binary = root / "program"
            subprocess.run(["cc", "-Wall", "-Wextra", "-Werror", "-O2", "-I", str(SOURCE.parent), "-o", str(binary), str(program), str(SOURCE)],
                           check=True)
            result = subprocess.run([str(binary), str(control)], capture_output=True, text=True, check=True)
            adjusted, now_ms, clamped = map(int, result.stdout.split())
            self.assertEqual(adjusted, 20_000_000_000)
            self.assertGreater(now_ms, 0)
            self.assertEqual(clamped, 0)

    def test_zero_and_missing_offsets_are_unchanged(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            control = root / "clock"
            control.write_bytes(struct.pack("<q", 0))
            program = root / "program.c"
            program.write_text(
                '#include <stdio.h>\n'
                '#include "clock_domain.h"\n'
                'int main(int argc, char **argv) { (void)argc;\n'
                '  droiddeck_clock_domain_set_control(argv[1]);\n'
                '  printf("%lld\\n", (long long)droiddeck_clock_domain_adjust_ns(30LL * 1000000000LL));\n'
                '  droiddeck_clock_domain_set_control(argv[2]);\n'
                '  printf("%lld\\n", (long long)droiddeck_clock_domain_adjust_ns(30LL * 1000000000LL));\n'
                '  return 0;\n'
                '}\n'
            )
            binary = root / "program"
            subprocess.run(["cc", "-Wall", "-Wextra", "-Werror", "-O2", "-I", str(SOURCE.parent), "-o", str(binary), str(program), str(SOURCE)],
                           check=True)
            result = subprocess.run([str(binary), str(control), str(root / "missing")], capture_output=True, text=True, check=True)
            self.assertEqual(result.stdout.split(), ["30000000000", "30000000000"])

    def test_replaced_control_file_updates_offset_without_invalidating_readers(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            control = root / "clock"
            replacement = root / "replacement"
            control.write_bytes(struct.pack("<q", 10_000_000_000))
            replacement.write_bytes(struct.pack("<q", 20_000_000_000))
            program = root / "program.c"
            program.write_text(
                '#include <stdio.h>\n'
                '#include "clock_domain.h"\n'
                'int main(int argc, char **argv) { (void)argc;\n'
                '  droiddeck_clock_domain_set_control(argv[1]);\n'
                '  printf("%lld\\n", (long long)droiddeck_clock_domain_adjust_ns(30LL * 1000000000LL));\n'
                '  droiddeck_clock_domain_set_control(argv[2]);\n'
                '  printf("%lld\\n", (long long)droiddeck_clock_domain_adjust_ns(30LL * 1000000000LL));\n'
                '  return 0;\n'
                '}\n'
            )
            binary = root / "program"
            subprocess.run(["cc", "-Wall", "-Wextra", "-Werror", "-O2", "-I", str(SOURCE.parent), "-o", str(binary), str(program), str(SOURCE)],
                           check=True)
            result = subprocess.run([str(binary), str(control), str(replacement)], capture_output=True, text=True, check=True)
            self.assertEqual(result.stdout.split(), ["20000000000", "10000000000"])


if __name__ == "__main__":
    unittest.main()
