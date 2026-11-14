from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[2]
TABLET = ROOT / "tools/ubuntu/patches/kwin/0005-forward-nested-tablet-input.patch"
HOST_EVENTS = {
    "zwp_tablet_seat_v2": ["tablet_added", "tool_added", "pad_added"],
    "zwp_tablet_v2": ["name", "id", "path", "done", "removed", "bustype"],
    "zwp_tablet_tool_v2": ["type", "hardware_serial", "hardware_id_wacom", "capability", "done", "removed", "proximity_in",
                           "proximity_out", "down", "up", "motion", "pressure", "distance", "tilt", "rotation", "slider", "wheel",
                           "button", "frame"],
    "zwp_tablet_pad_v2": ["group", "path", "buttons", "done", "button", "enter", "leave", "removed"],
    "zwp_tablet_pad_group_v2": ["buttons", "ring", "strip", "modes", "done", "mode_switch", "dial"],
}


def added(name):
    text = TABLET.read_text()
    begin = text.index(f"diff --git a/{name} b/{name}")
    end = text.find("\ndiff --git ", begin + 1)
    section = text[begin:end if end != -1 else len(text)]
    return "".join(line[1:] + "\n" for line in section.splitlines() if line.startswith("+") and not line.startswith("+++"))


def block(text, start, end="\n}\n"):
    begin = text.index(start)
    return text[begin:text.index(end, begin) + len(end)]


def tablet_code():
    return added("src/backends/wayland/wayland_tablet.cpp")


def pure_parts():
    text = tablet_code()
    return [
        block(text, "struct WaylandTabletToolFrame\n", "\n};\n"),
        block(text, "struct WaylandTabletToolActions\n", "\n};\n"),
        block(text, "static WaylandTabletToolActions planTabletToolFrame("),
        block(text, "static QPointF tabletTiltToOutput("),
        block(text, "static qreal tabletRotationToOutput("),
    ]


PREAMBLE = r'''
#include <algorithm>
#include <cassert>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <utility>
#include <vector>

using qreal = double;
using quint32 = uint32_t;

struct QPointF
{
    qreal xp = 0;
    qreal yp = 0;
    QPointF() = default;
    QPointF(qreal x, qreal y) : xp(x), yp(y) {}
    qreal x() const { return xp; }
    qreal y() const { return yp; }
};

inline QPointF operator+(QPointF a, QPointF b) { return QPointF(a.x() + b.x(), a.y() + b.y()); }
inline QPointF operator-(QPointF a, QPointF b) { return QPointF(a.x() - b.x(), a.y() - b.y()); }

struct QSizeF
{
    qreal w;
    qreal h;
    QSizeF(qreal width, qreal height) : w(width), h(height) {}
    qreal width() const { return w; }
    qreal height() const { return h; }
};

template<typename T>
struct QList : std::vector<T>
{
    using std::vector<T>::vector;
    void append(const T &value) { this->push_back(value); }
    bool contains(const T &value) const { return std::find(this->begin(), this->end(), value) != this->end(); }
    bool removeOne(const T &value)
    {
        auto it = std::find(this->begin(), this->end(), value);
        if (it == this->end()) {
            return false;
        }
        this->erase(it);
        return true;
    }
};

inline qreal qDegreesToRadians(qreal degrees) { return degrees * M_PI / 180; }
inline qreal qRadiansToDegrees(qreal radians) { return radians * 180 / M_PI; }

class OutputTransform
{
public:
    enum Kind { Normal, Rotate90, Rotate180, Rotate270, FlipX, FlipX90, FlipX180, FlipX270 };
    OutputTransform(Kind kind) : m_kind(kind) {}
    OutputTransform inverted() const
    {
        return m_kind == Rotate90 ? Rotate270 : m_kind == Rotate270 ? Rotate90 : m_kind;
    }
    QPointF map(const QPointF &p, const QSizeF &b) const
    {
        switch (m_kind) {
        case Normal: return p;
        case Rotate90: return QPointF(p.y(), b.width() - p.x());
        case Rotate180: return QPointF(b.width() - p.x(), b.height() - p.y());
        case Rotate270: return QPointF(b.height() - p.y(), p.x());
        case FlipX: return QPointF(b.width() - p.x(), p.y());
        case FlipX90: return QPointF(p.y(), p.x());
        case FlipX180: return QPointF(p.x(), b.height() - p.y());
        case FlipX270: return QPointF(b.height() - p.y(), b.width() - p.x());
        }
        return p;
    }

private:
    Kind m_kind;
};
'''

MAIN = r'''
using P = WaylandTabletToolFrame::Proximity;
using Buttons = std::vector<std::pair<quint32, bool>>;

static WaylandTabletToolActions plan(P proximity, bool down, bool up, bool axes, Buttons buttons, bool inProximity, bool tipDown, QList<quint32> held)
{
    WaylandTabletToolFrame frame;
    frame.proximity = proximity;
    frame.down = down;
    frame.up = up;
    frame.axesChanged = axes;
    frame.buttons = buttons;
    return planTabletToolFrame(frame, inProximity, tipDown, held);
}

static bool same(qreal a, qreal b) { return std::fabs(a - b) < 1e-9; }
static bool sameAngle(qreal a, qreal b) { const qreal d = std::fmod(std::fabs(a - b), 360.0); return d < 1e-9 || 360 - d < 1e-9; }

int main()
{
    {
        auto a = plan(P::In, false, false, true, {}, false, false, {});
        assert(a.proximityIn && !a.axis && !a.tipDown && !a.proximityOut);
    }
    {
        auto a = plan(P::In, true, false, true, {}, false, false, {});
        assert(a.proximityIn && a.tipDown && !a.axis);
    }
    {
        auto a = plan(P::Unchanged, true, true, true, {}, true, false, {});
        assert(a.tipDown && a.tipUp && !a.axis);
    }
    {
        auto a = plan(P::Unchanged, false, false, true, {}, true, true, {});
        assert(a.axis && !a.tipDown && !a.tipUp);
    }
    {
        auto a = plan(P::Unchanged, false, true, true, {}, true, false, {});
        assert(!a.tipUp && a.axis);
    }
    {
        auto a = plan(P::Out, false, true, false, {{331, false}}, true, true, {331});
        assert(a.tipUp && a.proximityOut && a.buttons == Buttons({{331, false}}));
    }
    {
        auto a = plan(P::In, false, false, true, {}, true, false, {});
        assert(!a.proximityIn && !a.proximityOut && a.axis);
    }
    {
        auto a = plan(P::Out, true, false, true, {{331, true}}, false, false, {});
        assert(!a.proximityIn && !a.proximityOut && !a.axis && !a.tipDown && a.buttons.empty());
    }
    {
        auto a = plan(P::Unchanged, true, false, true, {{331, true}}, false, false, {});
        assert(!a.axis && !a.tipDown && a.buttons.empty());
    }
    {
        auto a = plan(P::Unchanged, false, false, false, {{331, true}, {331, true}, {332, false}}, true, false, {});
        assert(a.buttons == Buttons({{331, true}}));
    }
    {
        auto a = plan(P::Unchanged, false, false, false, {{331, true}, {331, false}}, true, false, {});
        assert(a.buttons == Buttons({{331, true}, {331, false}}));
    }

    const OutputTransform::Kind kinds[] = {OutputTransform::Normal, OutputTransform::Rotate90, OutputTransform::Rotate180, OutputTransform::Rotate270,
                                           OutputTransform::FlipX, OutputTransform::FlipX90, OutputTransform::FlipX180, OutputTransform::FlipX270};
    const QSizeF panel(1280, 720);
    for (OutputTransform::Kind kind : kinds) {
        const OutputTransform transform(kind);
        for (QPointF v : {QPointF(1, 0), QPointF(0, 1), QPointF(30, -20)}) {
            const QPointF p(200, 100);
            const QPointF moved = transform.inverted().map(p + v, panel) - transform.inverted().map(p, panel);
            const QPointF tilt = tabletTiltToOutput(transform, v);
            assert(same(tilt.x(), moved.x()) && same(tilt.y(), moved.y()));
        }
        for (qreal degrees : {0.0, 10.0, 90.0, 135.0, 200.0, 359.5}) {
            const qreal rotation = tabletRotationToOutput(transform, degrees);
            assert(rotation >= 0 && rotation < 360);
            const qreal r = qDegreesToRadians(degrees), out = qDegreesToRadians(rotation);
            const QPointF direction = tabletTiltToOutput(transform, QPointF(std::sin(r), -std::cos(r)));
            assert(same(direction.x(), std::sin(out)) && same(direction.y(), -std::cos(out)));
        }
    }

    // What clients of the patched KWin 6.6.6 received on outputs turned with kscreen-doctor.
    struct Row {
        OutputTransform::Kind kind;
        QPointF tiltX;
        QPointF tiltY;
        qreal up;
        qreal right;
    };
    const Row rows[] = {
        {OutputTransform::Normal, QPointF(30, 0), QPointF(0, 30), 0, 90},
        {OutputTransform::Rotate90, QPointF(0, 30), QPointF(-30, 0), 90, 180},
        {OutputTransform::Rotate180, QPointF(-30, 0), QPointF(0, -30), 180, 270},
        {OutputTransform::Rotate270, QPointF(0, -30), QPointF(30, 0), 270, 0},
        {OutputTransform::FlipX, QPointF(-30, 0), QPointF(0, 30), 0, 270},
        {OutputTransform::FlipX90, QPointF(0, 30), QPointF(30, 0), 270, 180},
        {OutputTransform::FlipX180, QPointF(30, 0), QPointF(0, -30), 180, 90},
        {OutputTransform::FlipX270, QPointF(0, -30), QPointF(-30, 0), 90, 0},
    };
    for (const Row &row : rows) {
        const QPointF x = tabletTiltToOutput(row.kind, QPointF(30, 0)), y = tabletTiltToOutput(row.kind, QPointF(0, 30));
        assert(same(x.x(), row.tiltX.x()) && same(x.y(), row.tiltX.y()));
        assert(same(y.x(), row.tiltY.x()) && same(y.y(), row.tiltY.y()));
        assert(sameAngle(tabletRotationToOutput(row.kind, 0), row.up));
        assert(sameAngle(tabletRotationToOutput(row.kind, 90), row.right));
    }

    puts("ok");
    return 0;
}
'''


@unittest.skipUnless(shutil.which("c++"), "needs a C++ compiler")
class TabletLogicTest(unittest.TestCase):
    def test_frames_keep_libinput_order_and_tilt_turns_with_the_output(self):
        with tempfile.TemporaryDirectory() as tmp:
            source = Path(tmp) / "tablet.cpp"
            binary = Path(tmp) / "tablet"
            source.write_text(PREAMBLE + "\n".join(pure_parts()) + MAIN)
            subprocess.run(["c++", "-std=c++20", "-Wall", "-Wextra", "-Werror", str(source), "-o", str(binary)], check=True)
            result = subprocess.run([str(binary)], capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertEqual(result.stdout.strip(), "ok")


class NestedTabletTest(unittest.TestCase):
    def test_only_the_gate_binds_the_host_tablet(self):
        display = added("src/backends/wayland/wayland_display.cpp")
        self.assertEqual(display.count("wl_registry_bind("), 1)
        self.assertRegex(display, r'static const bool (\w+) = qEnvironmentVariableIntValue\("DROIDDECK_NESTED_TABLET"\) == 1;\n'
                                  r' +if \(\1\) \{\n[^\n]*wl_registry_bind\(')

    def test_every_listener_handles_every_event(self):
        listeners = re.findall(r"static const (\w+)_listener \w+\{\n(.*?)\n    \};", tablet_code(), re.S)
        self.assertEqual({name: re.findall(r"^        \.(\w+) = ", body, re.M) for name, body in listeners}, HOST_EVENTS)

    def test_the_desktop_forwards_the_tablet_with_the_pointer_fallback(self):
        script = (ROOT / "tools/linuxfs/desktop/droiddeck-desktop").read_text()
        self.assertRegex(script, r"(?m)^export .*\bDROIDDECK_NESTED_TABLET=1\b")
        self.assertRegex(script, r"(?m)^export .*\bKWIN_WAYLAND_EMULATE_TABLET=1\b")


if __name__ == "__main__":
    unittest.main()
