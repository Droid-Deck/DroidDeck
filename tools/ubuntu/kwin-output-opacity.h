#pragma once

#include "scene/surfaceitem.h"
#include "window.h"
#include "workspace.h"

#include <KWayland/Client/compositor.h>
#include <KWayland/Client/region.h>
#include <KWayland/Client/surface.h>

namespace KWin::Wayland
{

inline void updateIndependentOpacity(WaylandLayer *layer, WaylandOutput *output, const QSize &size)
{
    const QString prefix = QStringLiteral("DroidDeckWindow:wl:");
    if (!output->name().startsWith(prefix)) {
        return;
    }
    bool opaque = false;
    for (const auto window : workspace()->windows()) {
        if (window->internalId().toString() != output->name().mid(prefix.size())) {
            continue;
        }
        const auto surface = window->surfaceItem();
        if (surface && window->opacity() == 1.0) {
            const Region uncovered = Region(window->frameGeometry().toRect())
                - surface->mapToScene(surface->opaque());
            opaque = uncovered.isEmpty();
        }
        break;
    }
    const QRegion region = opaque ? QRegion(QRect(QPoint(), size)) : QRegion();
    const auto native = output->backend()->display()->compositor()->createRegion(region);
    layer->surface()->setOpaqueRegion(native.get());
}

}
