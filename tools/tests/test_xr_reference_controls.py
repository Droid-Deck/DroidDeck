import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[2]


class XrReferenceControlsTest(unittest.TestCase):
    def test_chord_requires_opt_in_and_never_persists_its_mode(self):
        compiler = shutil.which(os.environ.get('CXX', 'c++'))
        if not compiler:
            self.skipTest('A host C++ compiler is required')
        source = (ROOT / 'app/src/main/cpp/questxr/quest_xr.cpp').read_text()
        start = source.index('    void referenceControls(')
        function = source[start:source.index('\n    void updateReference(', start)]
        harness = r'''
#include <cassert>
#include <cstdio>
#include <cstring>
#include <string>
#include <fstream>
#include <unistd.h>
using XrTime = long long;
#define DROIDDECK_XR_STICK_CLICK 4
#define __android_log_print(...) ((void)0)
struct Activity { const char *externalDataPath; };
struct App { Activity *activity; };
struct Input { int buttons = DROIDDECK_XR_STICK_CLICK; };
struct Test {
    App *app_;
    unsigned frames_ = 0;
    int referenceMode_ = 0;
    std::string referenceRequestedMode_;
    bool referenceChordEnabled_ = false;
    XrTime referenceChordStart_ = 0;
    bool referenceChordLatched_ = false;
    bool handActive_[2]{true, true};
    Input inputs_[2];
''' + function + r'''
};
int main(int argc, char **argv) {
    assert(argc == 2);
    Activity activity{argv[1]};
    App app{&activity};
    Test test{&app};
    const std::string mode = std::string(argv[1]) + "/xr-controller-reference.mode";
    const std::string chord = std::string(argv[1]) + "/xr-controller-reference.chord";
    test.referenceControls(1000000000LL);
    test.referenceControls(2000000000LL);
    assert(test.referenceMode_ == 0 && access(mode.c_str(), F_OK) != 0);
    std::ofstream(chord).close();
    test.referenceControls(3000000000LL);
    test.referenceControls(3800000000LL);
    assert(test.referenceMode_ == 1 && access(mode.c_str(), F_OK) != 0);
    test.referenceControls(5000000000LL);
    assert(test.referenceMode_ == 1);
    test.inputs_[0].buttons = 0;
    test.referenceControls(6000000000LL);
    test.inputs_[0].buttons = DROIDDECK_XR_STICK_CLICK;
    test.referenceControls(7000000000LL);
    test.referenceControls(7800000000LL);
    assert(test.referenceMode_ == 2 && access(mode.c_str(), F_OK) != 0);
    Test restarted{&app};
    restarted.referenceControls(1000000000LL);
    assert(restarted.referenceMode_ == 0);
    std::ofstream(mode) << "overlay\n";
    test.referenceControls(9000000000LL);
    assert(test.referenceMode_ == 1);
    test.inputs_[0].buttons = 0;
    test.referenceControls(10000000000LL);
    test.inputs_[0].buttons = DROIDDECK_XR_STICK_CLICK;
    test.referenceControls(11000000000LL);
    test.referenceControls(11800000000LL);
    assert(test.referenceMode_ == 2);
    std::string requested;
    std::ifstream(mode) >> requested;
    assert(requested == "overlay");
    unlink(chord.c_str());
    test.referenceControls(12000000000LL);
    assert(test.referenceMode_ == 1);
    unlink(mode.c_str());
    test.referenceControls(13000000000LL);
    assert(test.referenceMode_ == 0);
}
'''
        with tempfile.TemporaryDirectory() as folder:
            folder = Path(folder)
            code = folder / 'reference.cpp'
            code.write_text(harness)
            binary = folder / 'reference-test'
            subprocess.run([compiler, '-std=c++20', '-Wall', '-Werror', str(code), '-o', str(binary)], check=True)
            subprocess.run([str(binary), str(folder)], check=True, timeout=10)
