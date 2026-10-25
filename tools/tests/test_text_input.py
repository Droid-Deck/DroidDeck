"""Exercise the text bridge through a real X11 client and Xutf8LookupString."""
import ctypes
import ctypes.util
import locale
import os
import select
import shutil
import signal
import socket
import struct
import subprocess
import sys
import tempfile
import threading
import time
import unittest
from pathlib import Path

HELPER = Path(__file__).resolve().parents[1] / "linuxfs/overlay/usr/local/bin/droiddeck-text-input"
XVFB = shutil.which("Xvfb")
FOCUS_PROPERTY = b"GAMESCOPE_KEYBOARD_FOCUS_DISPLAY"
SAMPLE = "中文ABC123，。é😀"
MAX_TEXT = 65536
ACK_OK = b"\x01"
ACK_FAILED = b"\x00"

KEY_PRESS_MASK = 1 << 0
KEY_RELEASE_MASK = 1 << 1
STRUCTURE_NOTIFY_MASK = 1 << 17
KEY_PRESS = 2
KEY_RELEASE = 3
MAP_NOTIFY = 19
MAPPING_NOTIFY = 34
REVERT_TO_PARENT = 2
PROPERTY_REPLACE = 0
INPUT_STYLE = 0x0408  # XIMPreeditNothing | XIMStatusNothing
LC_ALL = 6  # glibc
CODESET = 14  # glibc
KEYSYM_BACKSPACE = 0xff08
KEYSYM_RETURN = 0xff0d

_X11 = None


class XKeyEvent(ctypes.Structure):
    _fields_ = [
        ("type", ctypes.c_int),
        ("serial", ctypes.c_ulong),
        ("send_event", ctypes.c_int),
        ("display", ctypes.c_void_p),
        ("window", ctypes.c_ulong),
        ("root", ctypes.c_ulong),
        ("subwindow", ctypes.c_ulong),
        ("time", ctypes.c_ulong),
        ("x", ctypes.c_int),
        ("y", ctypes.c_int),
        ("x_root", ctypes.c_int),
        ("y_root", ctypes.c_int),
        ("state", ctypes.c_uint),
        ("keycode", ctypes.c_uint),
        ("same_screen", ctypes.c_int),
    ]


class XModifierKeymap(ctypes.Structure):
    _fields_ = [
        ("max_keypermod", ctypes.c_int),
        ("modifiermap", ctypes.POINTER(ctypes.c_ubyte)),
    ]


def _load_x11():
    lib = ctypes.CDLL(ctypes.util.find_library("X11") or "libX11.so.6")
    lib.XInitThreads.restype = ctypes.c_int
    lib.XInitThreads()  # the consumer reads events in its own thread
    lib.XOpenDisplay.argtypes = [ctypes.c_char_p]
    lib.XOpenDisplay.restype = ctypes.c_void_p
    lib.XCloseDisplay.argtypes = [ctypes.c_void_p]
    lib.XDefaultRootWindow.argtypes = [ctypes.c_void_p]
    lib.XDefaultRootWindow.restype = ctypes.c_ulong
    lib.XCreateSimpleWindow.argtypes = [ctypes.c_void_p, ctypes.c_ulong, ctypes.c_int, ctypes.c_int,
                                        ctypes.c_uint, ctypes.c_uint, ctypes.c_uint, ctypes.c_ulong,
                                        ctypes.c_ulong]
    lib.XCreateSimpleWindow.restype = ctypes.c_ulong
    lib.XDestroyWindow.argtypes = [ctypes.c_void_p, ctypes.c_ulong]
    lib.XSelectInput.argtypes = [ctypes.c_void_p, ctypes.c_ulong, ctypes.c_long]
    lib.XMapWindow.argtypes = [ctypes.c_void_p, ctypes.c_ulong]
    lib.XSetInputFocus.argtypes = [ctypes.c_void_p, ctypes.c_ulong, ctypes.c_int, ctypes.c_ulong]
    lib.XSync.argtypes = [ctypes.c_void_p, ctypes.c_int]
    lib.XPending.argtypes = [ctypes.c_void_p]
    lib.XPending.restype = ctypes.c_int
    lib.XNextEvent.argtypes = [ctypes.c_void_p, ctypes.c_void_p]
    lib.XConnectionNumber.argtypes = [ctypes.c_void_p]
    lib.XConnectionNumber.restype = ctypes.c_int
    lib.XSetLocaleModifiers.argtypes = [ctypes.c_char_p]
    lib.XOpenIM.argtypes = [ctypes.c_void_p, ctypes.c_void_p, ctypes.c_void_p, ctypes.c_void_p]
    lib.XOpenIM.restype = ctypes.c_void_p
    lib.XCreateIC.restype = ctypes.c_void_p
    lib.XDestroyIC.argtypes = [ctypes.c_void_p]
    lib.XCloseIM.argtypes = [ctypes.c_void_p]
    lib.Xutf8LookupString.argtypes = [ctypes.c_void_p, ctypes.POINTER(XKeyEvent),
                                      ctypes.POINTER(ctypes.c_char), ctypes.c_int,
                                      ctypes.POINTER(ctypes.c_ulong),
                                      ctypes.POINTER(ctypes.c_int)]
    lib.Xutf8LookupString.restype = ctypes.c_int
    lib.XDisplayKeycodes.argtypes = [ctypes.c_void_p, ctypes.POINTER(ctypes.c_int),
                                     ctypes.POINTER(ctypes.c_int)]
    lib.XRefreshKeyboardMapping.argtypes = [ctypes.c_void_p]
    lib.XGetKeyboardMapping.argtypes = [ctypes.c_void_p, ctypes.c_ubyte, ctypes.c_int,
                                        ctypes.POINTER(ctypes.c_int)]
    lib.XGetKeyboardMapping.restype = ctypes.POINTER(ctypes.c_ulong)
    lib.XQueryKeymap.argtypes = [ctypes.c_void_p, ctypes.c_void_p]
    lib.XFree.argtypes = [ctypes.c_void_p]
    lib.XGetModifierMapping.argtypes = [ctypes.c_void_p]
    lib.XGetModifierMapping.restype = ctypes.POINTER(XModifierKeymap)
    lib.XFreeModifiermap.argtypes = [ctypes.POINTER(XModifierKeymap)]
    lib.XFreeModifiermap.restype = ctypes.c_int
    lib.XInternAtom.argtypes = [ctypes.c_void_p, ctypes.c_char_p, ctypes.c_int]
    lib.XInternAtom.restype = ctypes.c_ulong
    lib.XChangeProperty.argtypes = [ctypes.c_void_p, ctypes.c_ulong, ctypes.c_ulong, ctypes.c_ulong,
                                    ctypes.c_int, ctypes.c_int, ctypes.c_void_p, ctypes.c_int]
    return lib


def libx11():
    global _X11
    if _X11 is None:
        _X11 = _load_x11()
    return _X11


class XTextConsumer:
    """An ordinary X11 client: it owns a focused window and keeps reading the key events the
    bridge injects, converting each one through its own input context."""

    def __init__(self, lib, display):
        self.lib = lib
        self.lock = threading.Lock()
        self._text = b""
        self._presses = []
        self._releases = []
        self._keysyms = []
        self._stopped = threading.Event()
        self._thread = None
        self._im = None
        self._ic = None
        self._dpy = lib.XOpenDisplay(display.encode())
        if not self._dpy:
            raise RuntimeError("the test client cannot open X display " + display)
        try:
            self._window = lib.XCreateSimpleWindow(self._dpy, lib.XDefaultRootWindow(self._dpy),
                                                   0, 0, 200, 100, 0, 0, 0)
            lib.XSelectInput(self._dpy, self._window,
                             KEY_PRESS_MASK | KEY_RELEASE_MASK | STRUCTURE_NOTIFY_MASK)
            lib.XMapWindow(self._dpy, self._window)
            self._wait_for_map(display)
            lib.XSetInputFocus(self._dpy, self._window, REVERT_TO_PARENT, 0)
            lib.XSync(self._dpy, False)
            self.snapshot = self.keymap()
            self._open_input_context()
        except Exception:
            self.close()
            raise

    def _wait_for_map(self, display):
        event = ctypes.create_string_buffer(192)
        deadline = time.monotonic() + 5
        while time.monotonic() < deadline:
            if self.lib.XPending(self._dpy):
                self.lib.XNextEvent(self._dpy, ctypes.cast(event, ctypes.c_void_p))
                if ctypes.cast(event, ctypes.POINTER(XKeyEvent)).contents.type == MAP_NOTIFY:
                    return
            else:
                select.select([self.lib.XConnectionNumber(self._dpy)], [], [], 0.05)
        raise RuntimeError("the test client window never became viewable on " + display)

    def _open_input_context(self):
        locale.setlocale(locale.LC_CTYPE, "C.UTF-8")
        self.lib.XSetLocaleModifiers(b"@im=none")
        self._im = self.lib.XOpenIM(self._dpy, None, None, None)
        if not self._im:
            raise RuntimeError("Xlib has no input method here, so Xutf8LookupString cannot run")
        self._ic = self.lib.XCreateIC(ctypes.c_void_p(self._im),
                                      ctypes.c_char_p(b"inputStyle"), ctypes.c_ulong(INPUT_STYLE),
                                      ctypes.c_char_p(b"clientWindow"), ctypes.c_ulong(self._window),
                                      ctypes.c_char_p(b"focusWindow"), ctypes.c_ulong(self._window),
                                      ctypes.c_void_p(0))
        if not self._ic:
            raise RuntimeError("Xlib refused an input context for Xutf8LookupString")
        self.lib.XSync(self._dpy, False)

    def start(self):
        self._thread = threading.Thread(target=self._pump, daemon=True)
        self._thread.start()

    def _pump(self):
        event = ctypes.create_string_buffer(192)
        raw = ctypes.cast(event, ctypes.c_void_p)
        fd = self.lib.XConnectionNumber(self._dpy)
        while not self._stopped.is_set():
            try:
                pending = self.lib.XPending(self._dpy)
            except (OSError, ValueError):
                return
            if not pending:
                try:
                    readable, _, _ = select.select([fd], [], [], 0.05)
                except (OSError, ValueError):
                    return
                if not readable:
                    continue
            while self.lib.XPending(self._dpy):
                self.lib.XNextEvent(self._dpy, raw)
                if ctypes.cast(event, ctypes.POINTER(ctypes.c_int)).contents.value == MAPPING_NOTIFY:
                    self.lib.XRefreshKeyboardMapping(raw)
                    continue
                key = ctypes.cast(event, ctypes.POINTER(XKeyEvent))
                if key.contents.type == KEY_PRESS:
                    self._press(key)
                elif key.contents.type == KEY_RELEASE:
                    with self.lock:
                        self._releases.append(key.contents.keycode)

    def _press(self, key):
        buffer = ctypes.create_string_buffer(64)
        keysym = ctypes.c_ulong(0)
        status = ctypes.c_int(0)
        count = self.lib.Xutf8LookupString(ctypes.c_void_p(self._ic), key, buffer, len(buffer),
                                           ctypes.byref(keysym), ctypes.byref(status))
        with self.lock:
            self._presses.append(key.contents.keycode)
            self._keysyms.append(keysym.value)
            if count > 0:
                self._text += buffer.raw[:count]

    def wait_for(self, count, timeout=10):
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            with self.lock:
                if len(self._presses) >= count and len(self._releases) >= count:
                    return True
            time.sleep(0.01)
        with self.lock:
            return len(self._presses) >= count and len(self._releases) >= count

    def counts(self):
        with self.lock:
            return len(self._presses), len(self._releases)

    def press_count(self):
        with self.lock:
            return len(self._presses)

    def press_keycodes(self):
        with self.lock:
            return sorted(self._presses)

    def release_keycodes(self):
        with self.lock:
            return sorted(self._releases)

    def keysyms(self):
        with self.lock:
            return list(self._keysyms)

    def text(self):
        with self.lock:
            raw = self._text
        try:
            return raw.decode("utf-8")
        except UnicodeDecodeError as error:
            raise AssertionError("the X11 client derived %r, which is not UTF-8: %s" % (raw, error))

    def keymap(self):
        first = ctypes.c_int(0)
        maximum = ctypes.c_int(0)
        self.lib.XDisplayKeycodes(self._dpy, ctypes.byref(first), ctypes.byref(maximum))
        count = maximum.value - first.value + 1
        per = ctypes.c_int(0)
        symbols = self.lib.XGetKeyboardMapping(self._dpy, first.value, count, ctypes.byref(per))
        if not symbols:
            raise RuntimeError("XGetKeyboardMapping returned nothing")
        values = [symbols[index] for index in range(count * per.value)]
        self.lib.XFree(ctypes.cast(symbols, ctypes.c_void_p))
        return (first.value, per.value, values)

    def modifier_codes(self):
        """Keycodes the server treats as modifiers; they are never replaceable."""
        mapping = self.lib.XGetModifierMapping(self._dpy)
        if not mapping:
            return set()
        try:
            width = mapping.contents.max_keypermod
            keys = mapping.contents.modifiermap
            return {keys[index] for index in range(8 * width) if keys[index]}
        finally:
            self.lib.XFreeModifiermap(mapping)

    def pressed_keys(self):
        keys = ctypes.create_string_buffer(32)
        self.lib.XQueryKeymap(self._dpy, ctypes.cast(keys, ctypes.c_void_p))
        return [index * 8 + bit for index in range(32) for bit in range(8)
                if keys.raw[index] >> bit & 1]

    def close(self):
        self._stopped.set()
        if self._thread is not None:
            self._thread.join(5)
            self._thread = None
        if self._ic:
            self.lib.XDestroyIC(ctypes.c_void_p(self._ic))
            self._ic = None
        if self._im:
            self.lib.XCloseIM(ctypes.c_void_p(self._im))
            self._im = None
        if self._dpy:
            if getattr(self, "_window", None):
                self.lib.XDestroyWindow(self._dpy, self._window)
            self.lib.XCloseDisplay(self._dpy)
            self._dpy = None


class XRoot:
    """Publishes the session properties the bridge reads from the main display's root window."""

    def __init__(self, lib, display):
        self.lib = lib
        self._dpy = lib.XOpenDisplay(display.encode())
        if not self._dpy:
            raise RuntimeError("the test client cannot open X display " + display)
        self.root = lib.XDefaultRootWindow(self._dpy)

    def set_string(self, name, value):
        data = ctypes.create_string_buffer(value, len(value))
        self.lib.XChangeProperty(self._dpy, self.root, self._atom(name), self._atom(b"STRING"),
                                 8, PROPERTY_REPLACE, ctypes.cast(data, ctypes.c_void_p), len(value))
        self.lib.XSync(self._dpy, False)

    def set_packed_cardinals(self, name, value):
        # gamescope 3.16.29 stores the display string's bytes packed into CARDINALs, little endian.
        padded = value + b"\0" * (-len(value) % 4)
        words = [int.from_bytes(padded[start:start + 4], "little") for start in range(0, len(padded), 4)]
        data = (ctypes.c_ulong * len(words))(*words)
        self.lib.XChangeProperty(self._dpy, self.root, self._atom(name), self._atom(b"CARDINAL"),
                                 32, PROPERTY_REPLACE, ctypes.cast(data, ctypes.c_void_p), len(words))
        self.lib.XSync(self._dpy, False)

    def _atom(self, name):
        return self.lib.XInternAtom(self._dpy, name, 0)

    def close(self):
        if self._dpy:
            self.lib.XCloseDisplay(self._dpy)
            self._dpy = None


LAUNCHER = (
    "import subprocess, sys, time\n"
    "child = subprocess.Popen([sys.executable, sys.argv[1], sys.argv[2]])\n"
    "open(sys.argv[3], 'w').write(str(child.pid))\n"
    "time.sleep(120)\n"
)


@unittest.skipUnless(XVFB, "Xvfb is not installed")
class TextInputTest(unittest.TestCase):
    maxDiff = 8000

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.dir = Path(self.temp.name)
        self.log = self.dir / "helper.log"
        try:
            self.lib = libx11()
        except OSError as error:
            self.fail("libX11 is missing, so no X11 client can check the bridge: %s" % error)

    def start_display(self):
        for number in range(90, 140):
            path = Path("/tmp/.X11-unix/X%d" % number)
            if path.exists():
                continue
            process = subprocess.Popen(
                [XVFB, ":%d" % number, "-screen", "0", "320x240x24", "-nolisten", "tcp"],
                stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            self.addCleanup(self.stop_display, process)
            deadline = time.monotonic() + 10
            while time.monotonic() < deadline:
                if path.exists():
                    return ":%d" % number
                if process.poll() is not None:
                    break
                time.sleep(0.02)
        self.fail("no Xvfb display could be started")

    @staticmethod
    def stop_display(process):
        if process.poll() is not None:
            return
        process.terminate()
        try:
            process.wait(timeout=5)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait(timeout=5)

    def consume(self, display):
        try:
            consumer = XTextConsumer(self.lib, display)
        except RuntimeError as error:
            self.fail(str(error))
        self.addCleanup(consumer.close)
        consumer.start()
        return consumer

    def start_helper(self, socket_path, display, game_display=None):
        self.assertTrue(HELPER.is_file(),
                        "the text bridge %s does not exist; the helper never started" % HELPER)
        env = dict(os.environ)
        env["DISPLAY"] = display
        env.pop("STEAM_GAME_DISPLAY_0", None)
        if game_display is not None:
            env["STEAM_GAME_DISPLAY_0"] = game_display
        stream = open(self.log, "wb")
        self.addCleanup(stream.close)
        process = subprocess.Popen([sys.executable, str(HELPER), str(socket_path)], env=env,
                                   stdin=subprocess.DEVNULL, stdout=stream, stderr=subprocess.STDOUT)
        self.addCleanup(self.stop_helper, process)
        self.wait_for_socket(process, socket_path)
        return process

    @staticmethod
    def stop_helper(process):
        if process.poll() is not None:
            return
        process.terminate()
        try:
            process.wait(timeout=10)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait(timeout=5)

    def wait_for_socket(self, process, socket_path, timeout=10):
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            if socket_path.exists():
                return
            if process.poll() is not None:
                self.fail("the text bridge exited with %s before serving %s; output: %r"
                          % (process.returncode, socket_path.name, self.output()))
            time.sleep(0.02)
        self.fail("the text bridge never listened on %s; output: %r"
                  % (socket_path.name, self.output()))

    def output(self):
        try:
            return self.log.read_text(errors="replace")[-2000:]
        except OSError:
            return ""

    def output_bytes(self):
        try:
            return self.log.read_bytes()
        except OSError:
            return b""

    def frame(self, socket_path, payload, timeout=15, shutdown=False):
        client = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        self.addCleanup(client.close)
        client.settimeout(timeout)
        client.connect(str(socket_path))
        client.sendall(payload)
        if shutdown:
            client.shutdown(socket.SHUT_WR)
        ack = b""
        while len(ack) < 1:
            try:
                chunk = client.recv(1)
            except socket.timeout:
                self.fail("the bridge did not answer within %s seconds" % timeout)
            if not chunk:
                break
            ack += chunk
        try:
            tail = client.recv(1)
        except socket.timeout:
            self.fail("the bridge kept the connection open after answering %r" % ack)
        self.assertEqual(b"", tail, "the bridge sent more than its one answer")
        return ack

    def commit(self, socket_path, text, timeout=15):
        payload = text if isinstance(text, bytes) else text.encode()
        return self.frame(socket_path, struct.pack(">I", len(payload)) + payload, timeout)

    def test_exact_unicode_reaches_a_real_x11_client(self):
        display = self.start_display()
        consumer = self.consume(display)
        socket_path = self.dir / "text-input.sock"
        process = self.start_helper(socket_path, display)
        self.assertEqual(ACK_OK, self.commit(socket_path, SAMPLE), "the bridge did not accept the text")
        self.assertTrue(consumer.wait_for(len(SAMPLE)),
                        "the X11 client saw %d presses and %d releases of the %d key pairs the text needs"
                        % (consumer.counts() + (len(SAMPLE),)))
        self.assertEqual(SAMPLE, consumer.text())
        self.assertEqual(consumer.press_keycodes(), consumer.release_keycodes(),
                         "a key was pressed without its release")
        self.stop_helper(process)
        self.assertEqual([], consumer.pressed_keys(), "the bridge left keys held down")
        self.assertEqual(consumer.snapshot, consumer.keymap(),
                         "the bridge did not restore the keyboard map")
        self.assertNotIn(SAMPLE.encode(), self.output_bytes(), "the bridge logged the text it typed")

    def test_adjacent_commits_reach_the_client_in_order(self):
        display = self.start_display()
        consumer = self.consume(display)
        socket_path = self.dir / "text-input.sock"
        process = self.start_helper(socket_path, display)
        for text in ("中文", " ", "abc"):
            self.assertEqual(ACK_OK, self.commit(socket_path, text),
                             "the bridge did not accept %r" % text)
        expected = "中文 abc"
        self.assertTrue(consumer.wait_for(len(expected)),
                        "the X11 client saw %d presses and %d releases of the %d key pairs the text needs"
                        % (consumer.counts() + (len(expected),)))
        self.assertEqual(expected, consumer.text())
        self.stop_helper(process)
        self.assertEqual(consumer.snapshot, consumer.keymap(),
                         "the bridge did not restore the keyboard map")
        self.assertEqual([], consumer.pressed_keys(), "the bridge left keys held down")

    def test_chinese_then_backspace_then_return_are_real_keysyms(self):
        display = self.start_display()
        consumer = self.consume(display)
        socket_path = self.dir / "text-input.sock"
        process = self.start_helper(socket_path, display)
        for text in ("中文", "\b", "\r"):
            self.assertEqual(ACK_OK, self.commit(socket_path, text),
                             "the bridge did not accept %r" % text)
        self.assertTrue(consumer.wait_for(4),
                        "the X11 client saw %d presses and %d releases of the 4 key pairs the text needs"
                        % consumer.counts())
        self.assertEqual([0x01000000|ord("中"),0x01000000|ord("文"),KEYSYM_BACKSPACE,KEYSYM_RETURN],
                         consumer.keysyms(),
                         "the soft controls must reach the client as real keysyms after the text")
        self.assertEqual("中文\b\r", consumer.text(),
                         "Xutf8LookupString is a raw lookup, not an editor; the controls stay literal")
        self.assertEqual(consumer.press_keycodes(), consumer.release_keycodes(),
                         "a key was pressed without its release")
        self.stop_helper(process)
        self.assertEqual(consumer.snapshot, consumer.keymap(),
                         "the bridge did not restore the keyboard map")
        self.assertEqual([], consumer.pressed_keys(), "the bridge left keys held down")

    def test_malformed_frames_are_refused_and_the_next_text_still_works(self):
        display = self.start_display()
        consumer = self.consume(display)
        socket_path = self.dir / "text-input.sock"
        process = self.start_helper(socket_path, display)
        frames = {
            "invalid utf8": (struct.pack(">I", 2) + b"\xff\xfe", False),
            "surrogate": (struct.pack(">I", 3) + b"\xed\xa0\x80", False),
            "nul": (struct.pack(">I", 3) + b"a\x00b", False),
            "empty text": (struct.pack(">I", 0), False),
            "over the size limit": (struct.pack(">I", MAX_TEXT + 1), False),
            "little endian length": (struct.pack("<I", 5) + b"hello", False),
            "truncated body": (struct.pack(">I", 10) + b"abc", True),
        }
        for name, (payload, shutdown) in frames.items():
            with self.subTest(name):
                self.assertEqual(ACK_FAILED, self.frame(socket_path, payload, shutdown=shutdown),
                                 "%s must be refused with ACK 0" % name)
        time.sleep(0.3)
        self.assertEqual(0, consumer.press_count(), "a refused request still injected keys")
        self.assertEqual(consumer.snapshot, consumer.keymap(),
                         "a refused request changed the keyboard map")
        self.assertEqual(ACK_OK, self.commit(socket_path, SAMPLE))
        self.assertTrue(consumer.wait_for(len(SAMPLE)),
                        "the bridge stopped serving after refusing a request")
        self.assertEqual(SAMPLE, consumer.text())
        self.stop_helper(process)
        self.assertEqual([], consumer.pressed_keys())
        self.assertEqual(consumer.snapshot, consumer.keymap())

    def test_stable_mappings_survive_a_delayed_consumer(self):
        display = self.start_display()
        try:
            consumer = XTextConsumer(self.lib, display)
        except RuntimeError as error:
            self.fail(str(error))
        self.addCleanup(consumer.close)
        socket_path = self.dir / "text-input.sock"
        process = self.start_helper(socket_path, display)
        later = "更多"
        self.assertEqual(ACK_OK, self.commit(socket_path, SAMPLE),
                         "the bridge did not accept the first frame")
        self.assertEqual(ACK_OK, self.commit(socket_path, later),
                         "the bridge did not accept the second frame")
        # Delay event lookup until both commits have been acknowledged.
        consumer.start()
        expected = SAMPLE + later
        self.assertTrue(consumer.wait_for(len(expected)),
                        "the X11 client saw %d presses and %d releases of the %d key pairs the text needs"
                        % (consumer.counts() + (len(expected),)))
        self.assertEqual(expected, consumer.text(),
                         "a delayed consumer must read every character exactly once, in order")
        self.assertEqual(consumer.press_keycodes(), consumer.release_keycodes(),
                         "a key was pressed without its release")
        self.stop_helper(process)
        self.assertEqual([], consumer.pressed_keys(), "the bridge left keys held down")
        self.assertEqual(consumer.snapshot, consumer.keymap(),
                         "the bridge did not restore the keyboard map")

    def test_a_frame_larger_than_the_empty_keymap_is_refused(self):
        display = self.start_display()
        consumer = self.consume(display)
        socket_path = self.dir / "text-input.sock"
        process = self.start_helper(socket_path, display)
        lowest, width, values = consumer.snapshot
        modifiers = consumer.modifier_codes()
        empty = 0
        for index in range(len(values) // width):
            keycode = lowest + index
            if keycode < 8 or keycode > 255 or keycode in modifiers:
                continue
            if not any(values[index * width:(index + 1) * width]):
                empty += 1
        self.assertLessEqual(empty, 248,
                             "the keycodes 8..255 alone cap every session at 248 slots")
        flood = "".join(chr(0x4E00 + offset) for offset in range(empty + 1))
        self.assertEqual(ACK_FAILED, self.commit(socket_path, flood),
                         "a frame needing more distinct keysyms than empty rows must be refused")
        time.sleep(0.5)
        self.assertEqual(0, consumer.press_count(), "a refused frame still injected keys")
        self.assertEqual(consumer.snapshot, consumer.keymap(),
                         "a refused frame changed the keyboard map")
        self.assertEqual(ACK_OK, self.commit(socket_path, "中文"),
                         "the refusal consumed the slots it merely measured")
        self.assertTrue(consumer.wait_for(2), "the next short text was never typed")
        self.assertEqual("中文", consumer.text())
        self.stop_helper(process)
        self.assertEqual([], consumer.pressed_keys())
        self.assertEqual(consumer.snapshot, consumer.keymap())

    def assert_routing(self, publish):
        main = self.start_display()
        game = self.start_display()
        main_consumer = self.consume(main)
        game_consumer = self.consume(game)
        root = XRoot(self.lib, main)
        self.addCleanup(root.close)
        publish(root, game.encode())
        socket_path = self.dir / "text-input.sock"
        self.start_helper(socket_path, main, game_display=game)
        self.assertEqual(ACK_OK, self.commit(socket_path, SAMPLE))
        self.assertTrue(game_consumer.wait_for(len(SAMPLE)),
                        "the display named by %s received no text" % FOCUS_PROPERTY.decode())
        self.assertEqual(SAMPLE, game_consumer.text())
        time.sleep(0.3)
        self.assertEqual(0, main_consumer.press_count(), "the text went to both displays")

    def test_two_displays_route_to_the_format8_property(self):
        self.assert_routing(lambda root, display: root.set_string(FOCUS_PROPERTY, display))

    def test_two_displays_route_to_the_gamescope_format32_property(self):
        self.assert_routing(lambda root, display: root.set_packed_cardinals(FOCUS_PROPERTY, display))

    def test_ambiguous_routing_injects_nothing(self):
        main = self.start_display()
        game = self.start_display()
        main_consumer = self.consume(main)
        game_consumer = self.consume(game)
        root = XRoot(self.lib, main)
        self.addCleanup(root.close)
        socket_path = self.dir / "text-input.sock"
        self.start_helper(socket_path, main, game_display=game)
        self.assertEqual(ACK_FAILED, self.commit(socket_path, SAMPLE),
                         "a missing focus property must not be guessed")
        root.set_string(FOCUS_PROPERTY, b":177")
        self.assertEqual(ACK_FAILED, self.commit(socket_path, SAMPLE),
                         "an unknown display must not be used")
        root.set_string(FOCUS_PROPERTY, b"\xff\xfe")
        self.assertEqual(ACK_FAILED, self.commit(socket_path, SAMPLE),
                         "unparseable property bytes must not be used")
        time.sleep(0.3)
        self.assertEqual((0, 0), (main_consumer.press_count(), game_consumer.press_count()),
                         "a refused route still injected keys")

    def test_sigterm_exits_and_removes_only_its_own_socket(self):
        display = self.start_display()
        socket_path = self.dir / "text-input.sock"
        process = self.start_helper(socket_path, display)
        foreign = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        self.addCleanup(foreign.close)
        foreign_path = self.dir / "someone-else.sock"
        foreign.bind(str(foreign_path))
        process.send_signal(signal.SIGTERM)
        try:
            process.wait(timeout=10)
        except subprocess.TimeoutExpired:
            self.fail("the text bridge ignored SIGTERM")
        self.assertFalse(socket_path.exists(), "the bridge left its own socket behind")
        self.assertTrue(foreign_path.exists(), "the bridge removed a socket it does not own")

    def test_exits_when_the_session_that_started_it_dies(self):
        display = self.start_display()
        socket_path = self.dir / "text-input.sock"
        self.assertTrue(HELPER.is_file(),
                        "the text bridge %s does not exist; the helper never started" % HELPER)
        pid_file = self.dir / "bridge.pid"
        launcher = subprocess.Popen([sys.executable, "-c", LAUNCHER, str(HELPER), str(socket_path),
                                     str(pid_file)],
                                    env=dict(os.environ, DISPLAY=display), stdin=subprocess.DEVNULL,
                                    stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        self.addCleanup(self.stop_helper, launcher)
        pid = 0
        deadline = time.monotonic() + 10
        while time.monotonic() < deadline:
            text = pid_file.read_text() if pid_file.exists() else ""
            if text.strip().isdigit():
                pid = int(text)
                break
            time.sleep(0.02)
        self.assertTrue(pid, "the launching session never reported the bridge pid")
        self.addCleanup(self.kill_pid, pid)
        self.wait_for_socket(launcher, socket_path)
        launcher.kill()
        launcher.wait(timeout=10)
        deadline = time.monotonic() + 15
        while time.monotonic() < deadline and self.pid_alive(pid):
            time.sleep(0.05)
        self.assertFalse(self.pid_alive(pid), "the bridge outlived the session that started it")
        self.assertFalse(socket_path.exists(), "the bridge left its socket behind")

    def test_a_stalled_client_does_not_block_the_service(self):
        display = self.start_display()
        consumer = self.consume(display)
        socket_path = self.dir / "text-input.sock"
        self.start_helper(socket_path, display)
        stalled = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        self.addCleanup(stalled.close)
        stalled.settimeout(5)
        stalled.connect(str(socket_path))
        stalled.sendall(struct.pack(">I", 40))
        started = time.monotonic()
        self.assertEqual(ACK_OK, self.commit(socket_path, "中文", timeout=20))
        self.assertLess(time.monotonic() - started, 20)
        self.assertTrue(consumer.wait_for(2), "the later request was never typed")
        self.assertEqual("中文", consumer.text())

    @staticmethod
    def kill_pid(pid):
        try:
            os.kill(pid, signal.SIGKILL)
        except OSError:
            pass

    @staticmethod
    def pid_alive(pid):
        try:
            os.kill(pid, 0)
        except ProcessLookupError:
            return False
        except PermissionError:
            return True
        try:
            state = Path("/proc/%d/stat" % pid).read_text().rsplit(")", 1)[1].split()[0]
        except (OSError, IndexError):
            return False
        return state != "Z"


if __name__ == "__main__":
    unittest.main()
