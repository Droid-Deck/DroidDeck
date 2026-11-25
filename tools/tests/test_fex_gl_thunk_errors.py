import os
import re
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest
from unittest import mock

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


REAL_HARNESS = r"""
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <dlfcn.h>
#include <GL/glx.h>
#include <GL/glxext.h>
#include <X11/Xmd.h>
#include <GL/glxproto.h>
#include <atomic>
#include <map>
#include <mutex>
#include <thread>
#include <unistd.h>
#include <X11/Xlibint.h>
using guest_long = long;

struct X11Manager {
  MUTEX_SOURCE
  std::map<_XDisplay*, _XDisplay*> displays;
  FORWARD_SOURCE
  FIND_SOURCE
  static XErrorHandler HostXSetErrorHandler(XErrorHandler Handler) { return XSetErrorHandler(Handler); }
  static void HostXLockDisplay(Display* a) { XLockDisplay(a); }
  static void HostXUnlockDisplay(Display* a) { XUnlockDisplay(a); }
};

static X11Manager x11_manager;
static int (*pending_forward)(void*, guest_long, int, int, int);

template<typename F>
static void MakeHostTrampolineForGuestFunctionAt(uintptr_t, uintptr_t, F** Func) { *Func = pending_forward; }

GUEST_SOURCE

HOST_SOURCE

static Display* host;
static Display* guest;
static std::atomic<int> app_calls;
static std::atomic<int> guest_calls;
static unsigned long app_serial;
static int app_error;
static int app_minor;
static std::atomic<bool> forwarding_started {false};

static int guest_forward(void* display, guest_long resourceid, int error_code, int request_code, int minor_code) {
  forwarding_started = true;
  forwarding_started.notify_one();
  return ForwardXError(static_cast<Display*>(display), resourceid, error_code, request_code, minor_code);
}

static int app_handler(Display* display, XErrorEvent* event) {
  if (display == guest) {
    guest_calls++;
    app_serial = event->serial;
    app_error = event->error_code;
    app_minor = event->minor_code;
  }
  app_calls++;
  return 0;
}

static bool host_usable_from_other_thread() {
  std::atomic<bool> done {false};
  std::thread other([&] {
    XNoOp(host);
    XSync(host, False);
    done = true;
  });
  for (int i = 0; i < 300 && !done; i++) {
    usleep(10000);
  }
  if (!done) {
    return false;
  }
  other.join();
  return true;
}

int main(int argc, char** argv) {
  XInitThreads();
  host = XOpenDisplay(nullptr);
  guest = XOpenDisplay(nullptr);
  if (!host || !guest) {
    return 2;
  }
  x11_manager.displays[guest] = host;
  pending_forward = guest_forward;
  XSetErrorHandler(app_handler);
  fexfn_impl_libGL_GL_SetGuestXErrorForward(0, 0);

  if (argc > 1) {
    auto library = dlopen("libGL.so.1", RTLD_NOW | RTLD_LOCAL);
    if (!library) {
      return 3;
    }
    auto choose = reinterpret_cast<decltype(&glXChooseFBConfig)>(dlsym(library, "glXChooseFBConfig"));
    auto get_proc = reinterpret_cast<decltype(&glXGetProcAddressARB)>(dlsym(library, "glXGetProcAddressARB"));
    auto query = reinterpret_cast<decltype(&glXQueryExtension)>(dlsym(library, "glXQueryExtension"));
    auto destroy = reinterpret_cast<decltype(&glXDestroyContext)>(dlsym(library, "glXDestroyContext"));
    if (!choose || !get_proc || !query || !destroy) {
      return 4;
    }
    auto create = reinterpret_cast<PFNGLXCREATECONTEXTATTRIBSARBPROC>(get_proc(
      reinterpret_cast<const GLubyte*>("glXCreateContextAttribsARB")));
    if (!create) {
      return 5;
    }
    int error_base, event_base, count;
    if (!query(host, &error_base, &event_base)) {
      return 6;
    }
    const int config_attribs[] = {GLX_RENDER_TYPE, GLX_RGBA_BIT, GLX_DRAWABLE_TYPE, GLX_WINDOW_BIT, None};
    auto configs = choose(host, DefaultScreen(host), config_attribs, &count);
    if (!configs || count == 0) {
      return 7;
    }
    const int supported[] = {GLX_CONTEXT_MAJOR_VERSION_ARB, 2, GLX_CONTEXT_MINOR_VERSION_ARB, 1, None};
    auto initial = create(host, configs[0], nullptr, True, supported);
    if (!initial) {
      return 8;
    }
    destroy(host, initial);
    if (strcmp(argv[1], "default") == 0) {
      XSetErrorHandler(nullptr);
      fexfn_impl_libGL_GL_SetGuestXErrorForward(0, 0);
    }
    const int unsupported[] = {GLX_CONTEXT_MAJOR_VERSION_ARB, 4, GLX_CONTEXT_MINOR_VERSION_ARB, 6, None};
    auto rejected = create(host, configs[0], nullptr, True, unsupported);
    XSync(host, False);
    auto fallback = create(host, configs[0], nullptr, True, supported);
    printf("valid_config=1 rejected=%d guest_errors=%d bad_fbconfig=%d minor_zero=%d fallback=%d\n",
           rejected == nullptr, guest_calls.load(), app_error == error_base + GLXBadFBConfig,
           app_minor == 0, fallback != nullptr);
    if (fallback) {
      destroy(host, fallback);
    }
    XFree(configs);
    fflush(stdout);
    _exit(0);
  }

  std::atomic<bool> guest_held {false};
  std::atomic<bool> host_reached {false};
  std::thread contender([&] {
    XLockDisplay(guest);
    guest_held = true;
    forwarding_started.wait(false);
    XNoOp(host);
    XFlush(host);
    host_reached = true;
    XUnlockDisplay(guest);
  });
  while (!guest_held) {
    usleep(1000);
  }
  XFreePixmap(host, 1);
  XSync(host, False);
  contender.join();
  bool usable = host_usable_from_other_thread();
  printf("app_calls=%d guest_calls=%d host_reached=%d usable=%d serial_from_guest=%d\n", app_calls.load(),
         guest_calls.load(), host_reached.load(), usable, app_serial != 0 && app_serial <= guest->request);
  fflush(stdout);
  _exit(0);
}
"""


def compile_cpp(source, output):
    return subprocess.run(["c++", "-std=c++20", "-O1", "-Wall", "-Werror", "-pthread", "-o", str(output), str(source), "-lX11"],
                          capture_output=True, text=True)


@unittest.skipUnless(shutil.which("c++"), "needs c++")
class FexGlThunkErrorForwardTest(unittest.TestCase):
    HARNESS_SOURCE = HARNESS
    DEPENDENCY_HEADERS = ("X11/Xlibint.h",)

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
        cls.addClassCleanup(shutil.rmtree, cls.tmp)
        probe = cls.tmp / "probe.cpp"
        probe.write_text("".join(f"#include <{header}>\n" for header in cls.DEPENDENCY_HEADERS)
                         + "int main() { return 0; }\n")
        if compile_cpp(probe, cls.tmp / "probe").returncode != 0:
            raise unittest.SkipTest("needs the native development files for this harness")
        source = cls.tmp / "harness.cpp"
        source.write_text(cls.HARNESS_SOURCE.replace("MUTEX_SOURCE", mutex).replace("FORWARD_SOURCE", forward)
                          .replace("FIND_SOURCE", find).replace("GUEST_SOURCE", guest).replace("HOST_SOURCE", host))
        cls.program = cls.tmp / "harness"
        result = compile_cpp(source, cls.program)
        if result.returncode != 0:
            raise AssertionError(result.stderr)

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


@unittest.skipUnless(shutil.which("c++") and shutil.which("Xvfb"), "needs c++ and Xvfb")
class FexGlThunkErrorForwardRealXlibTest(FexGlThunkErrorForwardTest):
    HARNESS_SOURCE = REAL_HARNESS
    DEPENDENCY_HEADERS = ("GL/glx.h", "GL/glxext.h", "X11/Xmd.h", "GL/glxproto.h", "X11/Xlibint.h")

    @classmethod
    def setUpClass(cls):
        super().setUpClass()
        read, write = os.pipe()
        cls.server = subprocess.Popen(["Xvfb", "-displayfd", str(write), "-nolisten", "tcp", "-screen", "0", "64x64x24"],
                                      pass_fds=(write,), stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        os.close(write)
        with os.fdopen(read) as stream:
            number = stream.readline().strip()
        if not number:
            cls.server.kill()
            cls.server.wait()
            raise unittest.SkipTest("Xvfb did not start")
        cls.display = ":" + number

    @classmethod
    def tearDownClass(cls):
        cls.server.terminate()
        cls.server.wait()

    def run_real(self, *args, expected_returncode=0):
        try:
            result = subprocess.run([str(self.program), *args], capture_output=True, text=True, timeout=20,
                                    env={**os.environ, "DISPLAY": self.display, "LIBGL_ALWAYS_SOFTWARE": "1",
                                         "MESA_GL_VERSION_OVERRIDE": "4.5"})
        except subprocess.TimeoutExpired:
            self.fail("the host error handler deadlocked")
        self.assertEqual(result.returncode, expected_returncode, result.stderr)
        return result.stdout.strip()

    def test_real_error_reaches_the_program_while_another_thread_holds_its_display(self):
        self.assertEqual(self.run_real(),
                         "app_calls=1 guest_calls=1 host_reached=1 usable=1 serial_from_guest=1")

    def test_valid_config_with_unsupported_version_reaches_handler_and_allows_fallback(self):
        self.assertEqual(self.run_real("glx"),
                         "valid_config=1 rejected=1 guest_errors=1 bad_fbconfig=1 minor_zero=1 fallback=1")

    def test_program_without_error_handler_keeps_default_fatal_behavior(self):
        self.run_real("default", expected_returncode=1)

    test_error_on_a_host_display_reaches_the_programs_handler = None
    test_forwarded_error_carries_the_programs_own_serial = None
    test_forwarding_never_swaps_the_programs_handler = None
    test_error_while_the_display_lock_is_held_does_not_block = None
    test_error_while_another_thread_holds_the_programs_display_lock_does_not_deadlock = None
    test_error_on_a_display_the_gl_thunk_does_not_own_keeps_the_previous_handler = None


class FexGlThunkDependencyProbeTest(unittest.TestCase):
    def test_missing_glx_headers_skip_the_real_harness(self):
        def compile_without_glx(source, output):
            self.assertEqual(source.name, "probe.cpp")
            self.assertIn("#include <GL/glxproto.h>", source.read_text())
            return subprocess.CompletedProcess([], 1, "", "missing GLX headers")

        with mock.patch(__name__ + ".compile_cpp", side_effect=compile_without_glx):
            try:
                with self.assertRaises(unittest.SkipTest):
                    FexGlThunkErrorForwardRealXlibTest.setUpClass()
            finally:
                FexGlThunkErrorForwardRealXlibTest.doClassCleanups()

    def test_real_harness_compile_errors_are_failures(self):
        results = [subprocess.CompletedProcess([], 0, "", ""),
                   subprocess.CompletedProcess([], 1, "", "harness compile error")]
        with mock.patch(__name__ + ".compile_cpp", side_effect=results):
            try:
                with self.assertRaisesRegex(AssertionError, "harness compile error"):
                    FexGlThunkErrorForwardRealXlibTest.setUpClass()
            finally:
                FexGlThunkErrorForwardRealXlibTest.doClassCleanups()


if __name__ == "__main__":
    unittest.main()
