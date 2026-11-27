// SPDX-License-Identifier: GPL-2.0-or-later
#pragma once

#include "window.h"

namespace KWin
{

inline bool independentOutputHidesWindow(const QString &outputName, Window *window, Window *ownerWindow = nullptr)
{
    const QString prefix = QStringLiteral("DroidDeckWindow:wl:");
    if (!outputName.startsWith(prefix)) {
        return false;
    }
    if (window->isDeleted()) {
        return true;
    }
    if (window->output() && window->output()->name().startsWith(prefix)
        && window->output()->name() != outputName) {
        return true;
    }
    const QString owner = outputName.mid(prefix.size());
    for (auto ancestor = window; ancestor; ancestor = ancestor->transientFor()) {
        if (ancestor->internalId().toString() == owner) {
            return false;
        }
    }
    if (ownerWindow && !ownerWindow->isDeleted() && window->isUnmanaged() && !window->transientFor()
        && window->pid() > 0 && window->pid() == ownerWindow->pid()
        && window->frameGeometry().intersects(ownerWindow->frameGeometry())
        && Window::belongToSameApplication(ownerWindow, window)) {
        return false;
    }
    return true;
}

}
