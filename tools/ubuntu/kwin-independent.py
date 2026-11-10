"""Apply the native-output extension to the matching KWin source tree."""
from pathlib import Path
import sys


def apply(source):
    base = Path(source) / "src/backends/wayland"

    def replace(name, old, new):
        path = base / name
        text = path.read_text()
        if new in text:
            return
        if old == "void WaylandOutput::frameDiscarded()\n{" and "void WaylandOutput::setPassthrough(" in text:
            return
        if text.count(old) != 1:
            raise ValueError(f"{name}: expected exactly one compatible source block")
        path.write_text(text.replace(old, new))

    implementation = Path(__file__).with_name("kwin-independent.cpp").read_bytes() + (
        b"\nnamespace KWin::Wayland {\n"
        b"void startIndependentWindows(WaylandBackend *backend) { new IndependentWindows(backend); }\n}\n")
    target = base / "droiddeck_independent.cpp"
    if not target.exists() or target.read_bytes() != implementation:
        target.write_bytes(implementation)
    passthrough = Path(__file__).with_name("kwin-passthrough.cpp").read_bytes()
    passthrough_target = base / "droiddeck_passthrough.cpp"
    if not passthrough_target.exists() or passthrough_target.read_bytes() != passthrough:
        passthrough_target.write_bytes(passthrough)
    layout = Path(__file__).with_name("kwin-output-layout.h").read_bytes()
    layout_target = base / "kwin-output-layout.h"
    if not layout_target.exists() or layout_target.read_bytes() != layout:
        layout_target.write_bytes(layout)
    ahb_output = Path(__file__).with_name("kwin-ahb-output.h").read_bytes()
    if not (base / "kwin-ahb-output.h").exists() or (base / "kwin-ahb-output.h").read_bytes() != ahb_output:
        (base / "kwin-ahb-output.h").write_bytes(ahb_output)
    opacity = Path(__file__).with_name("kwin-output-opacity.h").read_bytes()
    if not (base / "kwin-output-opacity.h").exists() or (base / "kwin-output-opacity.h").read_bytes() != opacity:
        (base / "kwin-output-opacity.h").write_bytes(opacity)
    window_filter = Path(__file__).with_name("kwin-independent-filter.h").read_bytes()
    if not (base / "kwin-independent-filter.h").exists() or (base / "kwin-independent-filter.h").read_bytes() != window_filter:
        (base / "kwin-independent-filter.h").write_bytes(window_filter)
    replace("../../compositor.cpp", '#include "window.h"',
            '#include "window.h"\n#include "backends/wayland/kwin-independent-filter.h"')
    scene_line = "        sceneView = std::make_unique<SceneView>(m_scene.get(), logicalOutput, backendOutput, primaryLayer);"
    legacy_filter = (
        '        if (backendOutput->name().startsWith("DroidDeckWindow:wl:")) {\n'
        "            sceneView->addWindowFilter([name = backendOutput->name()](Window *window) {\n"
        "                return independentOutputHidesWindow(name, window);\n"
        "            });\n"
        "        }")
    window_filter_block = (
        '        if (backendOutput->name().startsWith("DroidDeckWindow:wl:")) {\n'
        "            const auto owner = workspace()->findWindow(QUuid::fromString(\n"
        '                backendOutput->name().mid(QStringLiteral("DroidDeckWindow:wl:").size())));\n'
        "            sceneView->addWindowFilter([name = backendOutput->name(), owner = QPointer<Window>(owner)](Window *window) {\n"
        "                return independentOutputHidesWindow(name, window, owner.data());\n"
        "            });\n"
        "        }")
    if legacy_filter in (base / "../../compositor.cpp").read_text():
        replace("../../compositor.cpp", legacy_filter, window_filter_block)
    replace("../../compositor.cpp", scene_line, scene_line + "\n" + window_filter_block)
    replace("../../compositor.cpp",
            "        .requiredAlphaBits = 0,\n"
            "    });",
            '        .requiredAlphaBits = output->name().startsWith("DroidDeckWindow:wl:") ? 8u : 0u,\n'
            "    });")
    scene_path = base / "../../scene/scene.cpp"
    scene_text = scene_path.read_text()
    scene_text = scene_text.replace('#include "scene/windowitem.h"\n', "")
    scene_text = scene_text.replace(
        "    for (auto ancestor = item; ancestor; ancestor = ancestor->parentItem()) {\n"
        "        if (auto windowItem = dynamic_cast<WindowItem *>(ancestor)) {\n"
        "            if (shouldHideWindow(windowItem->window())) return false;\n"
        "            break;\n"
        "        }\n"
        "    }\n", "")
    if scene_path.read_text() != scene_text:
        scene_path.write_text(scene_text)
    replace("../../scene/workspacescene.cpp",
            "            if (!item->isVisible() || !painted_delegate->shouldRenderItem(item) || !painted_delegate->viewport().intersects(item->mapToView(item->boundingRect(), painted_delegate))) {",
            "            if (!item->isVisible() || painted_delegate->shouldHideWindow(static_cast<WindowItem *>(item)->window())\n"
            "                || !painted_delegate->shouldRenderItem(item) || !painted_delegate->viewport().intersects(item->mapToView(item->boundingRect(), painted_delegate))) {")
    replace("../../scene/workspacescene.cpp",
            "    const auto items = m_containerItem->sortedChildItems();\n"
            "    for (Item *item : items | std::views::reverse) {\n"
            "        if (!findOverlayCandidates(",
            "    const auto items = m_containerItem->sortedChildItems();\n"
            "    for (Item *item : items | std::views::reverse) {\n"
            "        if (painted_delegate->shouldHideWindow(static_cast<WindowItem *>(item)->window())) continue;\n"
            "        if (!findOverlayCandidates(")
    replace("wayland_layer.cpp", '#include "wayland_output.h"',
            '#include "wayland_output.h"\n#include "kwin-output-opacity.h"')
    replace("wayland_layer.cpp", "    m_subSurface->setPosition(logicalTarget.topLeft());",
            "    m_subSurface->setPosition(logicalTarget.topLeft());\n"
            "    updateIndependentOpacity(this, static_cast<WaylandOutput *>(m_output.get()), logicalTarget.size());")
    protocol = Path(__file__).resolve().parents[2] / "app/src/main/cpp/waylandcomp/src/ahb_broker_protocol.h"
    if not (base / "droiddeck_ahb_protocol.h").exists() or (base / "droiddeck_ahb_protocol.h").read_bytes() != protocol.read_bytes():
        (base / "droiddeck_ahb_protocol.h").write_bytes(protocol.read_bytes())
    replace("wayland_egl_backend.cpp", '#include "wayland_egl_backend.h"',
            '#include "wayland_egl_backend.h"\n#include "kwin-ahb-output.h"')
    replace("wayland_egl_backend.cpp", '#include "opengl/eglswapchain.h"',
            '#include "opengl/eglswapchain.h"\n#include "utils/drm_format_helper.h"')
    replace("wayland_egl_backend.cpp",
            "    if (!m_swapchain || m_swapchain->size() != nativeSize) {",
            "    const auto format = m_swapchain ? FormatInfo::get(m_swapchain->format()) : std::nullopt;\n"
            "    if (!m_swapchain || m_swapchain->size() != nativeSize || !format || format->alphaBits < m_requiredAlphaBits) {\n"
            "        m_swapchain.reset();\n"
            "        m_damageJournal.clear();")
    replace("wayland_egl_backend.cpp",
            "            m_swapchain = EglSwapchain::create(m_backend->drmDevice()->allocator(), m_backend->openglContext(), nativeSize, it.key(), it.value());",
            "            if (androidOutputEnabled()) {\n"
            "                m_swapchain = EglSwapchain::create(androidOutputAllocator(), m_backend->openglContext(), nativeSize, it.key(), it.value());\n"
            "                if (m_swapchain) qCInfo(KWIN_WAYLAND_BACKEND) << \"DroidDeck output AHB render target:\" << nativeSize << Qt::hex << it.key();\n"
            "            }\n"
            "            if (!androidOutputEnabled()) {\n"
            "                m_swapchain = EglSwapchain::create(m_backend->drmDevice()->allocator(), m_backend->openglContext(), nativeSize, it.key(), it.value());\n"
            "            }")
    replace("wayland_egl_backend.cpp",
            "bool WaylandEglLayer::importScanoutBuffer(GraphicsBuffer *buffer, const std::shared_ptr<OutputFrame> &frame)\n{\n",
            "bool WaylandEglLayer::importScanoutBuffer(GraphicsBuffer *buffer, const std::shared_ptr<OutputFrame> &frame)\n{\n"
            "    if (androidOutputEnabled()) return false;\n")
    replace("wayland_egl_backend.cpp",
            '            qCWarning(KWIN_WAYLAND_BACKEND) << "Could not find a suitable render format";',
            "            if (androidOutputEnabled()) {\n"
            "                for (const auto &candidate : suitableFormats) {\n"
            "                    m_swapchain = EglSwapchain::create(m_backend->drmDevice()->allocator(), m_backend->openglContext(), nativeSize, candidate.drmFormat, formatTable.value(candidate.drmFormat));\n"
            "                    if (m_swapchain) break;\n"
            "                }\n"
            "                if (m_swapchain) qCWarning(KWIN_WAYLAND_BACKEND) << \"DroidDeck output AHB unavailable; GBM fallback:\" << nativeSize;\n"
            "            }\n"
            "        }\n"
            "        if (!m_swapchain) {\n"
            '            qCWarning(KWIN_WAYLAND_BACKEND) << "Could not find a suitable render format";')
    output = base / "wayland_output.cpp"
    before = output.read_text()
    after = before.replace("window->internalId().toString(QUuid::WithoutBraces)",
                           "window->internalId().toString()")
    after = after.replace('if (!name().startsWith("DroidDeckWindow:"))',
                          'if (!this->name().startsWith("DroidDeckWindow:"))')
    after = after.replace('window->internalId().toString() == name())',
                          'window->internalId().toString() == this->name())')
    after = after.replace(
        "            static_cast<WaylandLayer *>(layer.get())->commit(PresentationMode::VSync);\n        }",
        "            static_cast<WaylandLayer *>(layer.get())->commit(PresentationMode::VSync);\n"
        "            layer->releaseBuffers();\n        }")
    after = after.replace(
        "    m_surface->attachBuffer(buffer);\n    wl_buffer_destroy(buffer);\n"
        "    m_mapped = true;\n    commitPassthrough();",
        "    m_surface->attachBuffer(buffer);\n    m_mapped = true;\n"
        "    commitPassthrough();\n    wl_buffer_destroy(buffer);")
    if after != before:
        output.write_text(after)
    replace("wayland_backend.cpp", '#include "moc_wayland_backend.cpp"',
            '#include "droiddeck_independent.cpp"\n#include "moc_wayland_backend.cpp"')
    replace("wayland_backend.cpp", "    createOutputs();",
            "    createOutputs();\n    startIndependentWindows(this);")
    replace("wayland_output.h", "    void frameDiscarded();",
            "    void setPassthrough(bool enabled);\n"
            "    void commitPassthrough();\n"
            "    void frameDiscarded();")
    replace("wayland_output.h", "    bool m_mapped = false;",
            "    bool m_mapped = false;\n    bool m_passthrough = false;")
    replace("wayland_output.cpp", "void WaylandOutput::frameDiscarded()\n{",
            "void WaylandOutput::setPassthrough(bool enabled)\n{\n"
            "    if (m_passthrough == enabled) return;\n"
            "    m_passthrough = enabled;\n"
            "    if (enabled) {\n"
            "        m_renderLoop->inhibit();\n"
            "        for (auto &layer : m_layers) {\n"
            "            layer->setEnabled(false);\n"
            "            static_cast<WaylandLayer *>(layer.get())->commit(PresentationMode::VSync);\n"
            "            layer->releaseBuffers();\n"
            "        }\n"
            "    } else {\n"
            "        m_renderLoop->uninhibit();\n"
            "    }\n"
            "    auto buffer = wp_single_pixel_buffer_manager_v1_create_u32_rgba_buffer(\n"
            "        m_backend->display()->singlePixelManager(), 0, 0, 0, enabled ? 0 : 0xFFFFFFFF);\n"
            "    m_surface->attachBuffer(buffer);\n"
            "    m_mapped = true;\n"
            "    commitPassthrough();\n"
            "    wl_buffer_destroy(buffer);\n"
            "}\n\n"
            "void WaylandOutput::commitPassthrough()\n{\n"
            "    m_viewport->setDestination(pixelSize());\n"
            "    m_surface->setScale(1);\n"
            "    m_surface->commit(KWayland::Client::Surface::CommitFlag::None);\n"
            "}\n\n"
            "void WaylandOutput::frameDiscarded()\n{")
    replace("wayland_backend.cpp", "namespace KWin\n{",
            "namespace KWin\n{\nnamespace Wayland { void startIndependentWindows(WaylandBackend *); }")
    replace("wayland_backend.cpp", "    return createOutput(name, size * scale, scale, false);",
            "    auto output = createOutput(name, size * scale, scale, false);\n"
            "    m_outputs << output;\n    Q_EMIT outputAdded(output);\n"
            "    Q_EMIT outputsQueried();\n    return output;")
    replace("wayland_output.cpp",
            "    connect(m_xdgShellSurface.get(), &XdgShellSurface::closeRequested, qApp, &QCoreApplication::quit);",
            "    connect(m_xdgShellSurface.get(), &XdgShellSurface::closeRequested, this, [this] {\n"
            '        if (!this->name().startsWith("DroidDeckWindow:")) { qApp->quit(); return; }\n'
            "        if (auto ws = workspace()) for (auto window : ws->windows()) {\n"
            '            if ("DroidDeckWindow:wl:" + window->internalId().toString() == this->name())\n'
            "                window->closeWindow();\n        }\n    });")
    replace("wayland_output.cpp", '#include "workspace.h"',
            '#include "workspace.h"\n#include "window.h"')
    replace("wayland_output.cpp", "void WaylandOutput::updateWindowTitle()\n{",
            "void WaylandOutput::updateWindowTitle()\n{\n"
            '    if (name().startsWith("DroidDeckWindow:")) {\n'
            "        m_xdgShellSurface->setTitle(name());\n        return;\n    }")
    replace("wayland_egl_backend.cpp", "    for (int z = 1; z < 5; z++) {",
            '    if (waylandOutput->name().startsWith("DroidDeckWindow:")) {\n'
            "        waylandOutput->setOutputLayers(std::move(layers));\n        return;\n    }\n"
            "    for (int z = 1; z < 5; z++) {")
    replace("wayland_output.cpp", "qreal WaylandOutput::presentationScale() const\n{",
            "qreal WaylandOutput::presentationScale() const\n{\n"
            '    if (name().startsWith("DroidDeckWindow:")) return 1.0;')
    replace("wayland_output.cpp",
            "auto mode = std::make_shared<OutputMode>(size * m_pendingScale, m_refreshRate);",
            'auto mode = std::make_shared<OutputMode>(size * (name().startsWith("DroidDeckWindow:") ? 1.0 : m_pendingScale), m_refreshRate);')
    replace("../../xdgshellwindow.cpp",
            "    if (nextClientSize.isEmpty()) {\n"
            "        QSizeF bounds = workspace()->clientArea(PlacementArea, this, moveResizeOutput()).size();\n"
            "        bounds.setWidth(std::max(1.0, bounds.width() - framePadding.width()));\n"
            "        bounds.setHeight(std::max(1.0, bounds.height() - framePadding.height()));\n"
            "        m_shellSurface->sendConfigureBounds(bounds.toSize());\n"
            "    }",
            "    const auto targetOutput = moveResizeOutput();\n"
            '    const bool independent = targetOutput && targetOutput->name().startsWith("DroidDeckWindow:");\n'
            "    if (nextClientSize.isEmpty() || independent) {\n"
            "        QSizeF bounds = independent ? targetOutput->geometryF().size()\n"
            "                                    : workspace()->clientArea(PlacementArea, this, targetOutput).size();\n"
            "        bounds.setWidth(std::max(1.0, bounds.width() - framePadding.width()));\n"
            "        bounds.setHeight(std::max(1.0, bounds.height() - framePadding.height()));\n"
            "        m_shellSurface->sendConfigureBounds(bounds.toSize());\n"
            "    }")
    replace("../../x11window.cpp",
            "    } else { // Client will not react on wm_delete_window. We have not choice\n"
            "        // but destroy his connection to the XServer.\n"
            "        killWindow();\n"
            "    }",
            "    } else {\n"
            "        if (!qEnvironmentVariableIsEmpty(\"BL_LAUNCH_DIR\")) {\n"
            "            return;\n"
            "        }\n"
            "        killWindow();\n"
            "    }")
    replace("../../scene/surfaceitem.cpp",
            "    m_texture.planes[0]->update(*view.image(), simplifyDamage(region));",
            "    const auto damage = simplifyDamage(region).intersected(Rect(QPoint(0, 0), buffer->size()));\n"
            "    m_texture.planes[0]->update(*view.image(), damage);")


if __name__ == "__main__":
    apply(sys.argv[1])
