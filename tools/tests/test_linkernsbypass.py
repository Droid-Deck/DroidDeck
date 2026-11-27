import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

REPO = Path(__file__).resolve().parents[2]
LIB = REPO / 'app/src/main/cpp/adrenotools/lib/linkernsbypass'

STUBS = {
    'android/api-level.h': 'static inline int android_get_device_api_level(void) { return 37; }\n',
    'android/log.h': 'int __android_log_print(int, const char *, const char *, ...);\n',
    'android/dlext.h': (
        '#pragma once\n'
        '#include <stddef.h>\n'
        '#include <stdint.h>\n'
        '#include <sys/types.h>\n'
        'struct android_namespace_t;\n'
        'enum { ANDROID_DLEXT_USE_LIBRARY_FD = 0x10, ANDROID_DLEXT_USE_NAMESPACE = 0x200 };\n'
        'typedef struct {\n'
        '  uint64_t flags; void *reserved_addr; size_t reserved_size; int relro_fd;\n'
        '  int library_fd; off64_t library_fd_offset; struct android_namespace_t *library_namespace;\n'
        '} android_dlextinfo;\n'
        'extern "C" void *android_dlopen_ext(const char *, int, const android_dlextinfo *);\n'
    ),
}


class LinkerNsBypassTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        compiler = shutil.which(os.environ.get('CXX', 'c++'))
        if compiler is None:
            raise unittest.SkipTest('A host C++ compiler is required')
        cls.temp = tempfile.TemporaryDirectory(prefix='droiddeck-linkernsbypass-')
        cls.addClassCleanup(cls.temp.cleanup)
        work = Path(cls.temp.name)
        for name, text in STUBS.items():
            (work / name).parent.mkdir(parents=True, exist_ok=True)
            (work / name).write_text(text)
        cls.binary = work / 'linkernsbypass-test'
        subprocess.run([
            compiler, '-std=c++17', '-O1', '-Wall', '-Wextra', '-Wno-missing-field-initializers',
            '-include', 'limits.h', '-include', 'sys/syscall.h',
            '-I', str(work), '-I', str(LIB),
            str(REPO / 'tools/tests/linkernsbypass_harness.cpp'), str(LIB / 'android_linker_ns.cpp'),
            '-Wl,--wrap=dlopen,--wrap=dlsym,--wrap=mprotect',
            '-o', str(cls.binary),
        ], check=True, capture_output=True, text=True, timeout=120)

    def run_mode(self, mode):
        result = subprocess.run([str(self.binary), mode], capture_output=True, text=True, timeout=10)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn(f'PASS {mode}', result.stdout)

    def test_loading_the_library_leaves_linker_internals_alone_until_first_use(self):
        self.run_mode('lazy')

    def test_dlopen_without_a_nearby_branch_is_not_followed(self):
        self.run_mode('far')

    def test_target_page_that_cannot_be_unguarded_is_never_called(self):
        self.run_mode('locked')

    def test_devices_refusing_write_and_exec_together_still_resolve(self):
        self.run_mode('no_write_exec')


if __name__ == '__main__':
    unittest.main()
