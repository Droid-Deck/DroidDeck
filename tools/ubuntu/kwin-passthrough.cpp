// SPDX-License-Identifier: GPL-2.0-or-later
#include "core/graphicsbuffer.h"
#include "core/colorspace.h"
#include "wayland/surface.h"
#include "wayland/subcompositor.h"
#include "wayland-client/viewporter.h"
#include "wayland_display.h"
#include <KWayland/Client/compositor.h>
#include <KWayland/Client/subcompositor.h>
#include <KWayland/Client/surface.h>
#include <wayland-client.h>
#include <map>

namespace KWin::Wayland
{
class RawWindow : public QObject
{
    struct Node {
        QPointer<SurfaceInterface> source;
        std::unique_ptr<KWayland::Client::Surface> surface;
        std::unique_ptr<KWayland::Client::SubSurface> sub;
        std::unique_ptr<WaylandClient::Viewport> viewport;
        bool dirty = true;
    };
    struct Item {
        SurfaceInterface *surface;
        QPoint position;
    };
    WaylandBackend *m_backend;
    QPointer<Window> m_window;
    WaylandOutput *m_output;
    std::map<SurfaceInterface *, std::unique_ptr<Node>> m_nodes;
    QList<QPointer<SurfaceInterface>> m_frameSurfaces;
    wl_callback *m_frame = nullptr;
    bool m_scheduled = false;
    bool m_active = false;
    bool m_allowed = true;
    uint64_t m_commits = 0;

    bool collect(SurfaceInterface *surface, const QPoint &position, QList<Item> &items, int depth)
    {
        if (!surface) return true;
        if (depth > 16 || items.size() >= 256) return false;
        for (auto sub : surface->below()) {
            if (!collect(sub->surface(), position + sub->position(), items, depth + 1)) return false;
        }
        items.append({surface, position + surface->offset()});
        for (auto sub : surface->above()) {
            if (!collect(sub->surface(), position + sub->position(), items, depth + 1)) return false;
        }
        return true;
    }

    void schedule()
    {
        if (m_scheduled) return;
        m_scheduled = true;
        QTimer::singleShot(0, this, [this] { m_scheduled = false; update(); });
    }

    void deactivate()
    {
        m_nodes.clear();
        if (m_frame) wl_callback_destroy(std::exchange(m_frame, nullptr));
        m_frameSurfaces.clear();
        if (m_active) m_output->setPassthrough(false);
        m_active = false;
    }

    void update()
    {
        if (!m_allowed || !m_window || m_window->opacity() != 1.0 || !m_window->surface() || !m_output->isReady()) {
            deactivate();
            return;
        }
        QList<Item> items;
        const auto bufferGeometry = m_window->bufferGeometry();
        const auto frame = m_window->frameGeometry();
        if (!collect(m_window->surface(), QPoint(qRound(bufferGeometry.x() - frame.x()), qRound(bufferGeometry.y() - frame.y())), items, 0)) {
            deactivate();
            return;
        }
        for (auto window : workspace()->stackingOrder()) {
            if (window == m_window || !window->surface()) continue;
            auto ancestor = window->transientFor();
            while (ancestor && ancestor != m_window) ancestor = ancestor->transientFor();
            if (!ancestor || (window->output() && window->output()->name() != m_output->name() &&
                              window->output()->name().startsWith("DroidDeckWindow:"))) continue;
            const auto geo = window->bufferGeometry();
            if (!collect(window->surface(), QPoint(qRound(geo.x() - frame.x()), qRound(geo.y() - frame.y())), items, 0)) {
                deactivate();
                return;
            }
        }
        for (const auto &item : items) {
            auto source = item.surface;
            auto buffer = source->buffer();
            if (source->bufferTransform() != OutputTransform::Normal || source->alphaMultiplier() != 1.0 ||
                (source->colorDescription() && source->colorDescription() != ColorDescription::sRGB) ||
                (buffer && !buffer->dmabufAttributes() && !buffer->shmAttributes())) {
                deactivate();
                return;
            }
        }
        if (!m_active) {
            m_output->setPassthrough(true);
            m_active = true;
        }
        QSet<SurfaceInterface *> wanted;
        wl_surface *previous = *m_output->surface();
        bool hasFrames = false;
        for (const auto &item : items) {
            auto source = item.surface;
            wanted.insert(source);
            auto &node = m_nodes[source];
            if (!node) {
                node = std::make_unique<Node>();
                node->source = source;
                node->surface.reset(m_backend->display()->compositor()->createSurface());
                node->sub.reset(m_backend->display()->subCompositor()->createSubSurface(node->surface.get(), m_output->surface()));
                node->viewport = m_backend->display()->viewporter()->createViewport(*node->surface);
                connect(source, &SurfaceInterface::committed, this, [this, source] {
                    auto it = m_nodes.find(source);
                    if (it != m_nodes.end()) it->second->dirty = true;
                    schedule();
                });
                connect(source, &SurfaceInterface::aboutToBeDestroyed, this, [this] { schedule(); });
            }
            wl_surface *outer = *node->surface;
            wl_subsurface *sub = *node->sub;
            wl_subsurface_set_position(sub, item.position.x(), item.position.y());
            wl_subsurface_place_above(sub, previous);
            previous = outer;
            if (node->dirty) {
                wl_buffer *buffer = source->buffer() ? m_backend->importBuffer(source->buffer()) : nullptr;
                wl_surface_attach(outer, buffer, 0, 0);
                if (buffer && !source->size().isEmpty()) {
                    node->viewport->setSource(source->bufferSourceBox());
                    node->viewport->setDestination(source->size().toSize());
                }
                wl_region *opaque = wl_compositor_create_region(*m_backend->display()->compositor());
                const auto size = source->size().toSize();
                if (source->opaque().contains(Rect(0, 0, size.width(), size.height()))) {
                    wl_region_add(opaque, 0, 0, size.width(), size.height());
                }
                wl_surface_set_opaque_region(outer, opaque);
                wl_region_destroy(opaque);
                wl_surface_damage(outer, 0, 0, INT32_MAX, INT32_MAX);
                wl_surface_commit(outer);
                node->dirty = false;
            }
            hasFrames |= source->hasFrameCallbacks();
        }
        for (auto it = m_nodes.begin(); it != m_nodes.end();) {
            if (!wanted.contains(it->first)) it = m_nodes.erase(it);
            else ++it;
        }
        if (!m_frame && hasFrames) {
            m_frameSurfaces.clear();
            for (const auto &item : items) m_frameSurfaces.append(item.surface);
            m_frame = wl_surface_frame(*m_output->surface());
            static const wl_callback_listener listener = {
                .done = [](void *data, wl_callback *callback, uint32_t time) {
                    auto self = static_cast<RawWindow *>(data);
                    wl_callback_destroy(callback);
                    self->m_frame = nullptr;
                    for (const auto &source : std::exchange(self->m_frameSurfaces, {})) {
                        if (source) { source->frameRendered(time); source->clearFifoBarrier(); }
                    }
                },
            };
            wl_callback_add_listener(m_frame, &listener, this);
        }
        m_output->commitPassthrough();
        m_backend->display()->flush();
        ++m_commits;
    }

public:
    RawWindow(WaylandBackend *backend, Window *window, WaylandOutput *output)
        : QObject(backend), m_backend(backend), m_window(window), m_output(output)
    {
        connect(window, &Window::frameGeometryChanged, this, [this] { schedule(); });
        connect(window, &Window::bufferGeometryChanged, this, [this] { schedule(); });
        connect(window, &Window::opacityChanged, this, [this] { schedule(); });
        connect(workspace(), &Workspace::windowAdded, this, [this] { schedule(); });
        connect(workspace(), &Workspace::windowRemoved, this, [this] { schedule(); });
        schedule();
    }
    ~RawWindow() override { deactivate(); }
    void refresh(bool allowed)
    {
        if (m_allowed != allowed || !m_active) { m_allowed = allowed; schedule(); }
    }
    bool active() const { return m_active; }
    uint64_t commits() const { return m_commits; }
};
}
