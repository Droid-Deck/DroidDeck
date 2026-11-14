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
#include <cstdint>
#include <cstdio>
#include <condition_variable>
#include <cstring>
#include <map>
#include <mutex>
#include <thread>
#include <X11/Xlibint.h>
using guest_long = long;

static std::mutex host_user_lock;
static std::mutex guest_user_lock;
static void guest_lock(Display*) { guest_user_lock.lock(); }
static void guest_unlock(Display*) { guest_user_lock.unlock(); }
static _XLockPtrs guest_lock_fns {guest_lock, guest_unlock};

struct X11Manager {
  MUTEX_SOURCE
  std::map<_XDisplay*, _XDisplay*> displays;
  FORWARD_SOURCE
  FIND_SOURCE
  static XErrorHandler HostXSetErrorHandler(XErrorHandler Handler) { return XSetErrorHandler(Handler); }
  static void HostXLockDisplay(Display*) { host_user_lock.lock(); }
  static void HostXUnlockDisplay(Display*) { host_user_lock.unlock(); }
};

static X11Manager x11_manager;
static int (*pending_forward)(void*, guest_long, int, int, int);

template<typename F>
static void MakeHostTrampolineForGuestFunctionAt(uintptr_t, uintptr_t, F** Func) { *Func = pending_forward; }

static int guest_set_calls;
#define XSetErrorHandler(h) (guest_set_calls++, ::XSetErrorHandler(h))
GUEST_SOURCE
#undef XSetErrorHandler

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

static int guest_forward(void* display, guest_long resourceid, int error_code, int request_code, int minor_code) {
  return ForwardXError(static_cast<Display*>(display), resourceid, error_code, request_code, minor_code);
}

static _XDisplay guest_connection;

static int raise_on_host(XErrorHandler handler, Display* display, XErrorEvent* event) {
  std::lock_guard held(host_user_lock);
  return handler(display, event);
}

int main(int argc, char** argv) {
  auto host = reinterpret_cast<Display*>(uintptr_t {0x1000});
  auto guest = &guest_connection;
  auto other = reinterpret_cast<Display*>(uintptr_t {0x3000});
  guest_connection.request = 44;
  guest_connection.last_request_read = 44;
  guest_connection.lock_fns = &guest_lock_fns;
  x11_manager.displays[guest] = host;
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
  event.serial = 9000;
  event.error_code = 163;
  event.request_code = 148;
  event.minor_code = 0;

  XSetErrorHandler(app_handler);
  if (argc > 1 && strcmp(argv[1], "reentry") == 0) {
    std::unique_lock lock(x11_manager.mutex);
    int reentry = raise_on_host(installed, host, &event);
    printf("reentry=%d app_calls=%d\n", reentry, app_calls);
    return 0;
  }
  if (argc > 1 && strcmp(argv[1], "crosslock") == 0) {
    host_user_lock.lock();
    std::mutex ready_lock;
    std::unique_lock ready(ready_lock);
    std::condition_variable guest_held;
    bool held = false;
    std::thread other_thread([&] {
      guest_user_lock.lock();
      {
        std::lock_guard notify(ready_lock);
        held = true;
      }
      guest_held.notify_one();
      host_user_lock.lock();
      host_user_lock.unlock();
      guest_user_lock.unlock();
    });
    guest_held.wait(ready, [&] { return held; });
    ready.unlock();
    int crossed = installed(host, &event);
    host_user_lock.unlock();
    other_thread.join();
    printf("crosslock=%d app_calls=%d\n", crossed, app_calls);
    return 0;
  }
  int owned = raise_on_host(installed, host, &event);
  XErrorHandler after = XSetErrorHandler(installed);
  int foreign = raise_on_host(installed, other, &event);

  printf("owned=%d app_calls=%d app_display_is_guest=%d serial=%lu error=%d request=%d minor=%d resource=%lx handler_kept=%d guest_set_calls=%d\n",
         owned, app_calls, app_display == guest, app_event.serial, app_event.error_code, app_event.request_code,
         app_event.minor_code, app_event.resourceid, after == app_handler, guest_set_calls);
  printf("foreign=%d previous_calls=%d previous_display_is_other=%d\n", foreign, previous_calls, previous_display == other);
  return 0;
}
"""


def compile_cpp(source, output):
    return subprocess.run(["c++", "-std=c++20", "-O1", "-Wall", "-Werror", "-pthread", "-o", str(output), str(source), "-lX11"],
                          capture_output=True, text=True)


@unittest.skipUnless(shutil.which("c++"), "needs c++")
class FexGlThunkErrorForwardTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        guest = added("ThunkLibs/libGL/libGL_Guest.cpp", "static int ForwardXError(", "\n}")
        host = added("ThunkLibs/libGL/libGL_Host.cpp", "static XErrorHandler HostPreviousXErrorHandler",
                     "    HostPreviousXErrorHandler = Previous;\n  }\n}")
        manager = "ThunkLibs/include/common/X11Manager.h"
        mutex = added(manager, "std::", " mutex;")
        forward = added(manager, "int (*GuestXErrorForward)", "= nullptr;")
        find = added(manager, "_XDisplay* FindGuestDisplay(", "    return nullptr;\n  }")
        cls.tmp = Path(tempfile.mkdtemp())
        probe = cls.tmp / "probe.cpp"
        probe.write_text("#include <X11/Xlibint.h>\nint main() { return 0; }\n")
        if compile_cpp(probe, cls.tmp / "probe").returncode != 0:
            raise unittest.SkipTest("needs the Xlib development files")
        source = cls.tmp / "harness.cpp"
        source.write_text(HARNESS.replace("MUTEX_SOURCE", mutex).replace("FORWARD_SOURCE", forward)
                          .replace("FIND_SOURCE", find).replace("GUEST_SOURCE", guest).replace("HOST_SOURCE", host))
        cls.program = cls.tmp / "harness"
        result = compile_cpp(source, cls.program)
        if result.returncode != 0:
            raise AssertionError(result.stderr)

    @classmethod
    def tearDownClass(cls):
        for child in cls.tmp.iterdir():
            child.unlink()
        cls.tmp.rmdir()

    def run_harness(self, *args):
        try:
            result = subprocess.run([str(self.program), *args], capture_output=True, text=True, timeout=10)
        except subprocess.TimeoutExpired:
            self.fail("the host error handler blocked")
        self.assertEqual(result.returncode, 0, result.stderr)
        return result.stdout.splitlines()

    def test_error_on_a_host_display_reaches_the_programs_handler(self):
        owned = self.run_harness()[0]
        self.assertIn("owned=7 app_calls=1 app_display_is_guest=1", owned)
        self.assertIn("error=163 request=148 minor=0 resource=ffffffff", owned)
        self.assertIn("handler_kept=1", owned)

    def test_forwarded_error_carries_the_programs_own_serial(self):
        self.assertIn(" serial=44 ", self.run_harness()[0])

    def test_forwarding_never_swaps_the_programs_handler(self):
        self.assertIn("guest_set_calls=0", self.run_harness()[0])

    def test_error_while_the_display_lock_is_held_does_not_block(self):
        self.assertEqual(self.run_harness("reentry"), ["reentry=7 app_calls=1"])

    def test_error_while_another_thread_holds_the_programs_display_lock_does_not_deadlock(self):
        self.assertEqual(self.run_harness("crosslock"), ["crosslock=7 app_calls=1"])

    def test_error_on_a_display_the_gl_thunk_does_not_own_keeps_the_previous_handler(self):
        self.assertEqual(self.run_harness()[1], "foreign=3 previous_calls=1 previous_display_is_other=1")


if __name__ == "__main__":
    unittest.main()
