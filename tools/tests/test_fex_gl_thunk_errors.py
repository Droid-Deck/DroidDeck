import re
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

PATCH = Path(__file__).resolve().parents[1] / "fex/patches/0005-gl-thunk-forward-x-errors.patch"


def added(path, start, end):
    text = PATCH.read_text()
    section = re.search(r"^diff --git a/%s .*?(?=^diff --git |\Z)" % re.escape(path), text, re.S | re.M)
    lines = [line[1:] for line in section.group(0).splitlines() if line.startswith("+") and not line.startswith("+++")]
    body = "\n".join(lines)
    first = body.index(start)
    last = body.index(end, first) + len(end)
    return body[first:last]


HARNESS = r"""
#include <X11/Xlib.h>
#include <cstdint>
#include <cstdio>
#include <map>
using guest_long = long;

struct X11Manager {
  std::map<_XDisplay*, _XDisplay*> host_to_guest;
  int (*GuestXErrorForward)(void*, int, guest_long, guest_long, int, int, int) = nullptr;
  _XDisplay* FindGuestDisplay(const _XDisplay* from) {
    auto it = host_to_guest.find(const_cast<_XDisplay*>(from));
    return it == host_to_guest.end() ? nullptr : it->second;
  }
  static XErrorHandler HostXSetErrorHandler(XErrorHandler Handler) { return XSetErrorHandler(Handler); }
};

static X11Manager x11_manager;
static int (*pending_forward)(void*, int, guest_long, guest_long, int, int, int);

template<typename F>
static void MakeHostTrampolineForGuestFunctionAt(uintptr_t, uintptr_t, F** Func) { *Func = pending_forward; }

GUEST_SOURCE

HOST_SOURCE

static Display* app_display;
static XErrorEvent app_event;
static int app_calls;
static int app_handler(Display* display, XErrorEvent* event) {
  app_display = display;
  app_event = *event;
  app_calls++;
  return 7;
}

static int previous_calls;
static Display* previous_display;
static int previous_handler(Display* display, XErrorEvent*) {
  previous_calls++;
  previous_display = display;
  return 3;
}

static int guest_forward(void* display, int type, guest_long resourceid, guest_long serial, int error_code, int request_code, int minor_code) {
  return ForwardXError(static_cast<Display*>(display), type, resourceid, serial, error_code, request_code, minor_code);
}

int main() {
  auto host = reinterpret_cast<Display*>(uintptr_t {0x1000});
  auto guest = reinterpret_cast<Display*>(uintptr_t {0x2000});
  auto other = reinterpret_cast<Display*>(uintptr_t {0x3000});
  x11_manager.host_to_guest[host] = guest;
  pending_forward = guest_forward;
  XSetErrorHandler(previous_handler);
  fexfn_impl_libGL_GL_SetGuestXErrorForward(0, 0);
  fexfn_impl_libGL_GL_SetGuestXErrorForward(0, 0);
  XErrorHandler installed = XSetErrorHandler(nullptr);
  XSetErrorHandler(installed);

  XErrorEvent event {};
  event.type = 0;
  event.display = host;
  event.resourceid = 0xffffffff;
  event.serial = 44;
  event.error_code = 163;
  event.request_code = 148;
  event.minor_code = 0;

  XSetErrorHandler(app_handler);
  int owned = installed(host, &event);
  XErrorHandler after = XSetErrorHandler(installed);
  int foreign = installed(other, &event);

  printf("owned=%d app_calls=%d app_display_is_guest=%d serial=%lu error=%d request=%d minor=%d resource=%lx handler_kept=%d\n",
         owned, app_calls, app_display == guest, app_event.serial, app_event.error_code, app_event.request_code,
         app_event.minor_code, app_event.resourceid, after == app_handler);
  printf("foreign=%d previous_calls=%d previous_display_is_other=%d\n", foreign, previous_calls, previous_display == other);
  return 0;
}
"""


@unittest.skipUnless(shutil.which("c++"), "needs c++")
class FexGlThunkErrorForwardTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        guest = added("ThunkLibs/libGL/libGL_Guest.cpp", "static int ForwardXError(", "\n}")
        host = added("ThunkLibs/libGL/libGL_Host.cpp", "static XErrorHandler HostPreviousXErrorHandler",
                     "    HostPreviousXErrorHandler = Previous;\n  }\n}")
        cls.tmp = Path(tempfile.mkdtemp())
        source = cls.tmp / "harness.cpp"
        source.write_text(HARNESS.replace("GUEST_SOURCE", guest).replace("HOST_SOURCE", host))
        cls.program = cls.tmp / "harness"
        result = subprocess.run(["c++", "-std=c++20", "-O1", "-Wall", "-Werror", "-o", str(cls.program), str(source), "-lX11"],
                                capture_output=True, text=True)
        if result.returncode != 0 and "X11/Xlib.h" in result.stderr:
            raise unittest.SkipTest("needs the Xlib headers")
        if result.returncode != 0:
            raise AssertionError(result.stderr)

    @classmethod
    def tearDownClass(cls):
        for child in cls.tmp.iterdir():
            child.unlink()
        cls.tmp.rmdir()

    def run_harness(self):
        result = subprocess.run([str(self.program)], capture_output=True, text=True, timeout=30)
        self.assertEqual(result.returncode, 0, result.stderr)
        return result.stdout.splitlines()

    def test_error_on_a_host_display_reaches_the_programs_handler(self):
        owned = self.run_harness()[0]
        self.assertIn("owned=7 app_calls=1 app_display_is_guest=1", owned)
        self.assertIn("serial=44 error=163 request=148 minor=0 resource=ffffffff", owned)
        self.assertIn("handler_kept=1", owned)

    def test_error_on_a_display_the_gl_thunk_does_not_own_keeps_the_previous_handler(self):
        self.assertEqual(self.run_harness()[1], "foreign=3 previous_calls=1 previous_display_is_other=1")


if __name__ == "__main__":
    unittest.main()
