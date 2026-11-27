// SPDX-License-Identifier: GPL-2.0-or-later
#include "window.h"
#include "workspace.h"
#include "inputmethod.h"
#include "main.h"
#include "kwin-output-layout.h"
#include "core/outputconfiguration.h"
#include <QFile>
#include <QJsonArray>
#include <QJsonDocument>
#include <QJsonObject>
#include <QPointer>
#include <QSaveFile>
#include <QSet>
#include <QTimer>
#include <utility>
#include "droiddeck_passthrough.cpp"

namespace KWin::Wayland
{
class IndependentWindows : public QObject
{
    struct Entry {
        QPointer<Window> window;
        WaylandOutput *output;
        RectF original;
        bool border;
        QPoint position;
        int slot;
        bool fullscreen;
        MaximizeMode maximized;
        std::shared_ptr<RawWindow> raw;
    };
    WaylandBackend *m_backend;
    QString m_path;
    QHash<QString, Entry> m_entries;
    QByteArray m_lastStatus;

    void restore(const QString &key)
    {
        auto entry = m_entries.take(key);
        entry.raw.reset();
        if (entry.window) {
            entry.window->setNoBorder(entry.border);
            entry.window->moveResize(entry.original);
            entry.window->setMaximize(entry.maximized & MaximizeVertical, entry.maximized & MaximizeHorizontal);
            entry.window->setFullScreen(entry.fullscreen);
        }
        m_backend->removeVirtualOutput(entry.output);
    }

    void poll()
    {
        auto ws = workspace();
        if (!ws) {
            return;
        }
        QFile file(m_path + "/window-outputs.json");
        QJsonObject request;
        if (file.open(QIODevice::ReadOnly) && file.size() <= 1024 * 1024) {
            request = QJsonDocument::fromJson(file.readAll()).object();
        }
        int cellWidth = 1;
        int cellHeight = 1;
        for (const auto &value : request.value("windows").toArray()) {
            const auto item = value.toObject();
            const int width = item.value("width").toInt();
            const int height = item.value("height").toInt();
            if (width > 0 && width <= 8192 && height > 0 && height <= 8192) {
                cellWidth = std::max(cellWidth, width);
                cellHeight = std::max(cellHeight, height);
            }
        }
        QSet<QString> wanted;
        QJsonArray result;
        QJsonArray managed;
        for (auto window : ws->windows()) {
            if (window->pid() <= 0) continue;
            const auto parent = window->transientFor();
            managed.append(QJsonObject{
                {"key", QString("wl:" + window->internalId().toString())},
                {"parentKey", parent ? "wl:" + parent->internalId().toString() : QString()},
                {"pid", int(window->pid())},
            });
        }
        const auto active = ws->activeWindow();
        const auto inputMethod = kwinApp()->inputMethod();
        if (QFile::exists(m_path + "/windows-enabled")) {
            for (const auto &value : request.value("windows").toArray()) {
                const auto item = value.toObject();
                const auto key = item.value("key").toString();
                const int width = item.value("width").toInt();
                const int height = item.value("height").toInt();
                if (!key.startsWith("wl:") || width < 1 || height < 1 || width > 8192 || height > 8192) {
                    continue;
                }
                Window *window = nullptr;
                for (auto candidate : ws->windows()) {
                    if ("wl:" + candidate->internalId().toString() == key) {
                        window = candidate;
                        break;
                    }
                }
                if (!window) {
                    continue;
                }
                if (!m_entries.contains(key)) {
                    int slot = 0;
                    for (; slot < 64; ++slot) {
                        bool occupied = false;
                        for (const auto &entry : std::as_const(m_entries)) {
                            if (entry.slot == slot) { occupied = true; break; }
                        }
                        if (!occupied) break;
                    }
                    const auto candidate = independentOutputPosition(slot, cellWidth, cellHeight);
                    if (!candidate) continue;
                    const auto original = window->frameGeometry();
                    const bool border = window->noBorder();
                    const bool fullscreen = window->isFullScreen();
                    const auto maximized = window->maximizeMode();
                    auto output = static_cast<WaylandOutput *>(m_backend->createVirtualOutput(
                        "DroidDeckWindow:" + key, {}, QSize(width, height), 1.0));
                    OutputConfiguration configuration;
                    auto changes = configuration.changeSet(output);
                    changes->enabled = true;
                    const QPoint position(candidate->x, candidate->y);
                    changes->pos = position;
                    changes->scale = 1.0;
                    ws->applyOutputConfiguration(configuration);
                    m_entries.insert(key, {window, output, original, border, position, slot, fullscreen, maximized,
                        std::make_shared<RawWindow>(m_backend, window, output)});
                }
                auto &entry = m_entries[key];
                const auto position = independentOutputPosition(entry.slot, cellWidth, cellHeight);
                if (!position) continue;
                wanted.insert(key);
                entry.position = QPoint(position->x, position->y);
                if (entry.output->position() != entry.position || entry.output->scale() != 1.0 ||
                    !entry.output->isEnabled()) {
                    OutputConfiguration configuration;
                    auto changes = configuration.changeSet(entry.output);
                    changes->pos = entry.position;
                    changes->scale = 1.0;
                    changes->enabled = true;
                    ws->applyOutputConfiguration(configuration);
                }
                if (window->isFullScreen()) window->setFullScreen(false);
                if (window->maximizeMode() != MaximizeRestore) window->setMaximize(false, false);
                window->setNoBorder(true);
                const auto pos = entry.position;
                const RectF geometry(pos.x(), pos.y(), width, height);
                if (window->frameGeometry() != geometry) window->moveResize(geometry);
                const auto frame = window->frameGeometry();
                const auto client = window->clientGeometry();
                const auto size = entry.output->pixelSize();
                entry.raw->refresh(qEnvironmentVariable("KWIN_DROIDDECK_OUTPUT_AHB") != QStringLiteral("1")
                    && !QFile::exists(m_path + "/window-passthrough-disabled"));
                result.append(QJsonObject{
                    {"key", key}, {"generation", item.value("generation")},
                    {"requestedWidth", width}, {"requestedHeight", height},
                    {"clientWidth", client.width()}, {"clientHeight", client.height()},
                    {"frameWidth", frame.width()}, {"frameHeight", frame.height()},
                    {"outputWidth", size.width()}, {"outputHeight", size.height()},
                    {"x", client.x()}, {"y", client.y()},
                    {"detached", window->output() && window->output()->name() == entry.output->name()
                        && frame.topLeft() == geometry.topLeft()},
                    {"match", frame.width() == width && frame.height() == height
                        && size == QSize(width, height)},
                    {"passthrough", entry.raw->active()},
                    {"forwardedCommits", qint64(entry.raw->commits())},
                });
            }
        }
        for (const auto &key : m_entries.keys()) {
            if (!wanted.contains(key) || !m_entries[key].window) {
                restore(key);
            }
        }
        const QByteArray body = QJsonDocument(QJsonObject{
                {"version", 1}, {"backend", "kwin-independent"},
                {"windows", result},
                {"managed", managed},
                {"activeKey", active ? "wl:" + active->internalId().toString() : QString()},
                {"textField", inputMethod && inputMethod->isActive()},
            }).toJson(QJsonDocument::Compact);
        if (body != m_lastStatus || !QFile::exists(m_path + "/window-host.json")) {
            QSaveFile status(m_path + "/window-host.json");
            if (status.open(QIODevice::WriteOnly)) {
                status.write(body);
                if (status.commit()) m_lastStatus = body;
            }
        }
    }

public:
    IndependentWindows(WaylandBackend *backend)
        : QObject(backend), m_backend(backend)
        , m_path(QString::fromLocal8Bit(qgetenv("BL_LAUNCH_DIR")) + "/agent")
    {
        auto timer = new QTimer(this);
        connect(timer, &QTimer::timeout, this, [this] { poll(); });
        timer->start(100);
    }
};
}
