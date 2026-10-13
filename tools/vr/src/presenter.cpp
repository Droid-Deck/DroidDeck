// See presenter.h.
#define VK_NO_PROTOTYPES
#include <vulkan/vulkan_core.h>
typedef struct _XDisplay Display;
typedef unsigned long Window;
typedef unsigned long VisualID;
#include <vulkan/vulkan_xlib.h>
#include <wayland-client.h>
#include <vulkan/vulkan_wayland.h>
#include "xdg-shell-client-protocol.h"

#include "presenter.h"

#include <atomic>
#include <chrono>
#include <condition_variable>
#include <cstdarg>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <dlfcn.h>
#include <fcntl.h>
#include <mutex>
#include <thread>
#include <unistd.h>
#include <vector>

#include "openvr_driver.h"

namespace droiddeck {
namespace {

void Log(const char *fmt, ...) __attribute__((format(printf, 1, 2)));
void Log(const char *fmt, ...) {
    char buf[1024];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(buf, sizeof(buf), fmt, ap);
    va_end(ap);
    if (vr::VRDriverLog()) vr::VRDriverLog()->Log(buf);
    fprintf(stderr, "[droiddeck] %s\n", buf);
}

// ---------------------------------------------------------------- Xlib, loaded at runtime

struct X11 {
    void *lib = nullptr;
    Display *(*OpenDisplay)(const char *);
    Window (*DefaultRootWindow)(Display *);
    int (*DefaultScreen)(Display *);
    int (*DisplayWidth)(Display *, int);
    int (*DisplayHeight)(Display *, int);
    Window (*CreateSimpleWindow)(Display *, Window, int, int, unsigned, unsigned, unsigned, unsigned long, unsigned long);
    unsigned long (*InternAtom)(Display *, const char *, int);
    int (*ChangeProperty)(Display *, Window, unsigned long, unsigned long, int, int, const unsigned char *, int);
    int (*MapWindow)(Display *, Window);
    int (*StoreName)(Display *, Window, const char *);
    int (*Flush)(Display *);

    bool Load() {
        lib = dlopen("libX11.so.6", RTLD_NOW | RTLD_LOCAL);
        if (!lib) return false;
#define X(name) if (!(*(void **)&name = dlsym(lib, "X" #name))) return false;
        X(OpenDisplay) X(DefaultRootWindow) X(DefaultScreen) X(DisplayWidth) X(DisplayHeight)
        X(CreateSimpleWindow) X(InternAtom) X(ChangeProperty) X(MapWindow) X(StoreName) X(Flush)
#undef X
        return true;
    }
};

// ---------------------------------------------------------------- Vulkan, loaded at runtime

#define VK_GLOBAL(X) X(CreateInstance)
#define VK_INSTANCE(X) \
    X(DestroyInstance) X(EnumeratePhysicalDevices) X(GetPhysicalDeviceQueueFamilyProperties) \
    X(GetPhysicalDeviceMemoryProperties) X(CreateDevice) X(GetDeviceProcAddr) X(CreateXlibSurfaceKHR) \
    X(CreateWaylandSurfaceKHR) \
    X(GetPhysicalDeviceSurfaceSupportKHR) X(GetPhysicalDeviceSurfaceCapabilitiesKHR) \
    X(GetPhysicalDeviceSurfaceFormatsKHR) X(GetPhysicalDeviceSurfacePresentModesKHR) X(GetPhysicalDeviceProperties)
#define VK_DEVICE(X) \
    X(GetDeviceQueue) X(CreateSwapchainKHR) X(DestroySwapchainKHR) X(GetSwapchainImagesKHR) X(AcquireNextImageKHR) \
    X(QueuePresentKHR) X(CreateCommandPool) X(AllocateCommandBuffers) X(BeginCommandBuffer) X(EndCommandBuffer) \
    X(ResetCommandBuffer) X(CmdPipelineBarrier) X(CmdBlitImage) X(CmdClearColorImage) X(QueueSubmit) X(CreateFence) \
    X(WaitForFences) X(ResetFences) X(CreateSemaphore) X(CreateImage) X(DestroyImage) X(GetImageMemoryRequirements) \
    X(AllocateMemory) X(FreeMemory) X(BindImageMemory) X(DeviceWaitIdle)

struct Vk {
    void *lib = nullptr;
    PFN_vkGetInstanceProcAddr GetInstanceProcAddr = nullptr;
#define X(n) PFN_vk##n n = nullptr;
    VK_GLOBAL(X) VK_INSTANCE(X) VK_DEVICE(X)
#undef X
};

struct Imported {
    uint64_t handle;
    VkImage image = VK_NULL_HANDLE;
    VkDeviceMemory memory = VK_NULL_HANDLE;
    uint32_t width = 0, height = 0;
};

struct PendingImport {
    uint64_t handle;
    int fd;
    uint32_t format, width, height;
};

VkFormat UnormOf(uint32_t f) {
    switch (f) {
    case VK_FORMAT_R8G8B8A8_SRGB: return VK_FORMAT_R8G8B8A8_UNORM;
    case VK_FORMAT_B8G8R8A8_SRGB: return VK_FORMAT_B8G8R8A8_UNORM;
    default: return (VkFormat)f;
    }
}

class Impl {
public:
    static Impl &Get() {
        static Impl impl;
        return impl;
    }

    void Add(const uint64_t handles[3], const int fds[3], uint32_t format, uint32_t w, uint32_t h) {
        std::lock_guard<std::mutex> lock(mutex_);
        for (int i = 0; i < 3; i++) {
            if (fds[i] < 0) continue;
            int fd = dup(fds[i]); // the import owns its fd; the driver keeps its own
            if (fd >= 0) pending_.push_back({handles[i], fd, format, w, h});
        }
        EnsureThread();
        cv_.notify_one();
    }

    void Remove(uint64_t handle) {
        std::lock_guard<std::mutex> lock(mutex_);
        removals_.push_back(handle);
        cv_.notify_one();
    }

    void Submit(const EyeFrame &f) {
        std::lock_guard<std::mutex> lock(mutex_);
        frame_ = f;
        frameAt_ = std::chrono::steady_clock::now();
        haveFrame_ = true;
        submitted_++;
        cv_.notify_one();
    }

    void Shutdown() {
        {
            std::lock_guard<std::mutex> lock(mutex_);
            exit_ = true;
            cv_.notify_one();
        }
        if (thread_.joinable()) thread_.join();
    }

private:
    void EnsureThread() {
        if (!started_) {
            started_ = true;
            thread_ = std::thread([this] { Run(); });
        }
    }

    // ------------------------------------------------------------ the presenting thread

    void Run() {
        const char *mode = getenv("DROIDDECK_VR_PRESENT");
        wayland_ = !(mode && !strcmp(mode, "x11")) && InitWayland();
        if (!wayland_ && !InitWindow()) {
            Log("presenter: unavailable, frames are not shown");
            return;
        }
        if (!InitVulkan()) {
            Log("presenter: unavailable, frames are not shown");
            return;
        }
        uint64_t shown = 0;
        auto lastLog = std::chrono::steady_clock::now();
        while (true) {
            EyeFrame f;
            std::chrono::steady_clock::time_point at;
            std::vector<PendingImport> imports;
            std::vector<uint64_t> removals;
            {
                std::unique_lock<std::mutex> lock(mutex_);
                cv_.wait(lock, [this] { return exit_ || haveFrame_ || !pending_.empty() || !removals_.empty(); });
                if (exit_) break;
                imports.swap(pending_);
                removals.swap(removals_);
                f = frame_;
                at = frameAt_;
                bool draw = haveFrame_;
                haveFrame_ = false;
                if (!draw) f.texture[0] = f.texture[1] = 0;
            }
            if (wl_) { wl_display_dispatch_pending(wl_); wl_display_flush(wl_); }
            for (auto &p : imports) Import(p);
            for (auto h : removals) Forget(h);
            // SteamVR hands the textures over at Present with no fence the driver can wait on: give the
            // compositor's GPU work a moment to land before copying, as ALVR does (a torn or half-drawn
            // frame otherwise).
            if (f.texture[0]) std::this_thread::sleep_until(at + guard_);
            if (f.texture[0] && Draw(f)) shown++;
            auto now = std::chrono::steady_clock::now();
            if (now - lastLog > std::chrono::seconds(10)) {
                Log("presenter: %llu frames shown, %llu submitted (last 10 s)", (unsigned long long)shown,
                    (unsigned long long)submitted_.exchange(0));
                shown = 0;
                lastLog = now;
            }
        }
        if (dev_) vk_.DeviceWaitIdle(dev_);
    }

    std::chrono::microseconds guard_{4000};

    // ------------------------------------------------------------ Wayland: straight to DroidDeck's compositor
    // The compositor the app runs (wayland-0 in the session's runtime dir, beside gamescope's socket)
    // takes a fullscreen toplevel at the panel's size: one hop and one scale fewer than the X11 overlay
    // through gamescope, whose output is the session's (often smaller) resolution.
    struct wl_display *wl_ = nullptr;
    struct wl_compositor *wlComp_ = nullptr;
    struct xdg_wm_base *wm_ = nullptr;
    struct wl_surface *wlSurf_ = nullptr;
    struct xdg_surface *xdgSurf_ = nullptr;
    struct xdg_toplevel *toplevel_ = nullptr;
    bool wayland_ = false, configured_ = false;
    int32_t cfgW_ = 0, cfgH_ = 0;

    static void OnGlobal(void *data, struct wl_registry *reg, uint32_t name, const char *iface, uint32_t version) {
        Impl *self = (Impl *)data;
        if (!strcmp(iface, wl_compositor_interface.name))
            self->wlComp_ = (struct wl_compositor *)wl_registry_bind(reg, name, &wl_compositor_interface, version < 4 ? version : 4);
        else if (!strcmp(iface, xdg_wm_base_interface.name))
            self->wm_ = (struct xdg_wm_base *)wl_registry_bind(reg, name, &xdg_wm_base_interface, 1);
    }
    static void OnGlobalRemove(void *, struct wl_registry *, uint32_t) {}
    static void OnPing(void *, struct xdg_wm_base *wm, uint32_t serial) { xdg_wm_base_pong(wm, serial); }
    static void OnSurfaceConfigure(void *data, struct xdg_surface *xs, uint32_t serial) {
        xdg_surface_ack_configure(xs, serial);
        ((Impl *)data)->configured_ = true;
    }
    static void OnToplevelConfigure(void *data, struct xdg_toplevel *, int32_t w, int32_t h, struct wl_array *) {
        Impl *self = (Impl *)data;
        if (w > 0 && h > 0) { self->cfgW_ = w; self->cfgH_ = h; }
    }
    static void OnToplevelClose(void *, struct xdg_toplevel *) {}

    bool InitWayland() {
        const char *name = getenv("DROIDDECK_VR_WAYLAND_DISPLAY");
        if (!name || !*name) name = "wayland-0";
        wl_ = wl_display_connect(name);
        if (!wl_) { Log("presenter: no Wayland display %s; using the X11 overlay", name); return false; }
        static const struct wl_registry_listener regListener = {OnGlobal, OnGlobalRemove};
        struct wl_registry *reg = wl_display_get_registry(wl_);
        wl_registry_add_listener(reg, &regListener, this);
        wl_display_roundtrip(wl_);
        if (!wlComp_ || !wm_) { Log("presenter: %s lacks wl_compositor or xdg_wm_base", name); return false; }
        static const struct xdg_wm_base_listener wmListener = {OnPing};
        xdg_wm_base_add_listener(wm_, &wmListener, this);
        wlSurf_ = wl_compositor_create_surface(wlComp_);
        xdgSurf_ = xdg_wm_base_get_xdg_surface(wm_, wlSurf_);
        static const struct xdg_surface_listener xsListener = {OnSurfaceConfigure};
        xdg_surface_add_listener(xdgSurf_, &xsListener, this);
        toplevel_ = xdg_surface_get_toplevel(xdgSurf_);
        static const struct xdg_toplevel_listener tlListener = {OnToplevelConfigure, OnToplevelClose, nullptr, nullptr};
        xdg_toplevel_add_listener(toplevel_, &tlListener, this);
        xdg_toplevel_set_title(toplevel_, "DroidDeck Cardboard VR");
        xdg_toplevel_set_app_id(toplevel_, "droiddeck-cardboard-vr");
        xdg_toplevel_set_fullscreen(toplevel_, nullptr);
        wl_surface_commit(wlSurf_);
        for (int i = 0; i < 20 && !configured_; i++) wl_display_roundtrip(wl_);
        // The size: the panel the Cardboard pass draws (two eyes, from the shared pose file), so nothing
        // scales the picture down on the way. The compositor's configure carries the session's output
        // size, which is often smaller; it is the fallback.
        width_ = height_ = 0;
        int fd = open("/run/droiddeck/droiddeck-headpose", O_RDONLY | O_CLOEXEC);
        if (fd >= 0) {
            uint32_t eye[2] = {0, 0};
            if (pread(fd, eye, sizeof(eye), 72) == (ssize_t)sizeof(eye) && eye[0] && eye[1]) { width_ = eye[0] * 2; height_ = eye[1]; }
            close(fd);
        }
        if (!width_ && cfgW_ > 0 && cfgH_ > 0) { width_ = (uint32_t)cfgW_; height_ = (uint32_t)cfgH_; }
        if (!width_) { width_ = 1920; height_ = 1080; }
        Log("presenter: %ux%u fullscreen window on Wayland display %s (configured %s)", width_, height_, name, configured_ ? "yes" : "no");
        return true;
    }

    bool InitWindow() {
        if (const char *g = getenv("DROIDDECK_VR_PRESENT_GUARD_MS")) guard_ = std::chrono::microseconds((long)(atof(g) * 1000));
        if (!x_.Load()) { Log("presenter: no libX11"); return false; }
        // gamescope's first Xwayland (:0) is where its overlays (mangoapp, Steam's) live; SteamVR's
        // processes inherit the games' display (:1), so it is not taken from DISPLAY.
        const char *name = getenv("DROIDDECK_VR_DISPLAY");
        if (!name || !*name) name = ":0";
        dpy_ = x_.OpenDisplay(name);
        if (!dpy_) { Log("presenter: cannot open X display %s", name); return false; }
        int scr = x_.DefaultScreen(dpy_);
        width_ = (uint32_t)x_.DisplayWidth(dpy_, scr);
        height_ = (uint32_t)x_.DisplayHeight(dpy_, scr);
        win_ = x_.CreateSimpleWindow(dpy_, x_.DefaultRootWindow(dpy_), 0, 0, width_, height_, 0, 0, 0);
        x_.StoreName(dpy_, win_, "DroidDeck Cardboard VR");
        // gamescope shows an external overlay above whatever has focus (mangoapp is one).
        // gamescope composites one external overlay (mangoapp, the perf HUD, usually holds it) and one Steam
        // overlay; the Steam overlay slot is free while nothing is shown over a game.
        const char *atom = getenv("DROIDDECK_VR_OVERLAY_ATOM");
        if (!atom || !*atom) atom = "STEAM_OVERLAY";
        unsigned long cardinal = 6; // XA_CARDINAL
        unsigned long one = 1;      // format 32 properties are longs in Xlib
        x_.ChangeProperty(dpy_, win_, x_.InternAtom(dpy_, atom, 0), cardinal, 32, 0 /* PropModeReplace */,
                          (const unsigned char *)&one, 1);
        x_.MapWindow(dpy_, win_);
        x_.Flush(dpy_);
        Log("presenter: %ux%u overlay window (%s) on X display %s", width_, height_, atom, name);
        return true;
    }

    bool InitVulkan() {
        vk_.lib = dlopen("libvulkan.so.1", RTLD_NOW | RTLD_LOCAL);
        if (!vk_.lib) { Log("presenter: no libvulkan"); return false; }
        vk_.GetInstanceProcAddr = (PFN_vkGetInstanceProcAddr)dlsym(vk_.lib, "vkGetInstanceProcAddr");
#define X(n) vk_.n = (PFN_vk##n)vk_.GetInstanceProcAddr(VK_NULL_HANDLE, "vk" #n);
        VK_GLOBAL(X)
#undef X
        const char *iexts[] = {"VK_KHR_surface", wayland_ ? "VK_KHR_wayland_surface" : "VK_KHR_xlib_surface"};
        VkApplicationInfo app = {VK_STRUCTURE_TYPE_APPLICATION_INFO, nullptr, "droiddeck-vr-presenter", 1, nullptr, 0, VK_API_VERSION_1_1};
        VkInstanceCreateInfo ici = {VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO, nullptr, 0, &app, 0, nullptr, 2, iexts};
        VkResult r = vk_.CreateInstance(&ici, nullptr, &inst_);
        if (r != VK_SUCCESS) { Log("presenter: vkCreateInstance %d", r); return false; }
#define X(n) vk_.n = (PFN_vk##n)vk_.GetInstanceProcAddr(inst_, "vk" #n);
        VK_INSTANCE(X)
#undef X
        uint32_t n = 1;
        VkPhysicalDevice pds[4];
        n = 4;
        if (vk_.EnumeratePhysicalDevices(inst_, &n, pds) < 0 || n == 0) { Log("presenter: no GPU"); return false; }
        pd_ = pds[0];
        VkPhysicalDeviceProperties props;
        vk_.GetPhysicalDeviceProperties(pd_, &props);
        vk_.GetPhysicalDeviceMemoryProperties(pd_, &mem_);

        if (wayland_) {
            VkWaylandSurfaceCreateInfoKHR wci = {VK_STRUCTURE_TYPE_WAYLAND_SURFACE_CREATE_INFO_KHR, nullptr, 0, wl_, wlSurf_};
            if ((r = vk_.CreateWaylandSurfaceKHR(inst_, &wci, nullptr, &surface_)) != VK_SUCCESS) { Log("presenter: wayland surface %d", r); return false; }
        } else {
            VkXlibSurfaceCreateInfoKHR sci = {VK_STRUCTURE_TYPE_XLIB_SURFACE_CREATE_INFO_KHR, nullptr, 0, dpy_, win_};
            if ((r = vk_.CreateXlibSurfaceKHR(inst_, &sci, nullptr, &surface_)) != VK_SUCCESS) { Log("presenter: xlib surface %d", r); return false; }
        }

        uint32_t nq = 0;
        vk_.GetPhysicalDeviceQueueFamilyProperties(pd_, &nq, nullptr);
        std::vector<VkQueueFamilyProperties> qf(nq);
        vk_.GetPhysicalDeviceQueueFamilyProperties(pd_, &nq, qf.data());
        qfam_ = UINT32_MAX;
        for (uint32_t i = 0; i < nq; i++) {
            VkBool32 present = VK_FALSE;
            vk_.GetPhysicalDeviceSurfaceSupportKHR(pd_, i, surface_, &present);
            if ((qf[i].queueFlags & VK_QUEUE_GRAPHICS_BIT) && present) { qfam_ = i; break; }
        }
        if (qfam_ == UINT32_MAX) { Log("presenter: no queue can present"); return false; }
        float prio = 1.0f;
        VkDeviceQueueCreateInfo qci = {VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO, nullptr, 0, qfam_, 1, &prio};
        const char *dexts[] = {"VK_KHR_swapchain", "VK_KHR_external_memory_fd"};
        VkDeviceCreateInfo dci = {VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO, nullptr, 0, 1, &qci, 0, nullptr, 2, dexts, nullptr};
        if ((r = vk_.CreateDevice(pd_, &dci, nullptr, &dev_)) != VK_SUCCESS) { Log("presenter: vkCreateDevice %d", r); return false; }
#define X(n) vk_.n = (PFN_vk##n)vk_.GetDeviceProcAddr(dev_, "vk" #n);
        VK_DEVICE(X)
#undef X
        vk_.GetDeviceQueue(dev_, qfam_, 0, &queue_);

        VkCommandPoolCreateInfo pci = {VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO, nullptr,
                                       VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT, qfam_};
        vk_.CreateCommandPool(dev_, &pci, nullptr, &pool_);
        VkCommandBufferAllocateInfo cai = {VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO, nullptr, pool_, VK_COMMAND_BUFFER_LEVEL_PRIMARY, 1};
        vk_.AllocateCommandBuffers(dev_, &cai, &cmd_);
        VkFenceCreateInfo fci = {VK_STRUCTURE_TYPE_FENCE_CREATE_INFO, nullptr, VK_FENCE_CREATE_SIGNALED_BIT};
        vk_.CreateFence(dev_, &fci, nullptr, &fence_);
        VkSemaphoreCreateInfo semci = {VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO, nullptr, 0};
        vk_.CreateSemaphore(dev_, &semci, nullptr, &acquired_);
        vk_.CreateSemaphore(dev_, &semci, nullptr, &rendered_);
        if (!CreateSwapchain()) return false;
        Log("presenter: Vulkan on %s, queue family %u", props.deviceName, qfam_);
        return true;
    }

    bool CreateSwapchain() {
        VkSurfaceCapabilitiesKHR caps;
        vk_.GetPhysicalDeviceSurfaceCapabilitiesKHR(pd_, surface_, &caps);
        uint32_t nf = 0;
        vk_.GetPhysicalDeviceSurfaceFormatsKHR(pd_, surface_, &nf, nullptr);
        std::vector<VkSurfaceFormatKHR> fmts(nf);
        vk_.GetPhysicalDeviceSurfaceFormatsKHR(pd_, surface_, &nf, fmts.data());
        VkSurfaceFormatKHR fmt = fmts.empty() ? VkSurfaceFormatKHR{VK_FORMAT_B8G8R8A8_UNORM, VK_COLOR_SPACE_SRGB_NONLINEAR_KHR} : fmts[0];
        for (auto &f : fmts)
            if (f.format == VK_FORMAT_B8G8R8A8_UNORM || f.format == VK_FORMAT_R8G8B8A8_UNORM) { fmt = f; break; }
        uint32_t npm = 0;
        vk_.GetPhysicalDeviceSurfacePresentModesKHR(pd_, surface_, &npm, nullptr);
        std::vector<VkPresentModeKHR> pms(npm);
        vk_.GetPhysicalDeviceSurfacePresentModesKHR(pd_, surface_, &npm, pms.data());
        VkPresentModeKHR pm = VK_PRESENT_MODE_FIFO_KHR;
        for (auto m : pms) if (m == VK_PRESENT_MODE_MAILBOX_KHR) pm = m;
        VkExtent2D ext = caps.currentExtent.width != UINT32_MAX ? caps.currentExtent : VkExtent2D{width_, height_};
        extent_ = ext;
        uint32_t count = caps.minImageCount + 1;
        if (caps.maxImageCount && count > caps.maxImageCount) count = caps.maxImageCount;
        VkCompositeAlphaFlagBitsKHR alpha = (caps.supportedCompositeAlpha & VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR)
            ? VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR : (VkCompositeAlphaFlagBitsKHR)(caps.supportedCompositeAlpha & -caps.supportedCompositeAlpha);
        VkSwapchainCreateInfoKHR sci = {VK_STRUCTURE_TYPE_SWAPCHAIN_CREATE_INFO_KHR};
        sci.surface = surface_;
        sci.minImageCount = count;
        sci.imageFormat = fmt.format;
        sci.imageColorSpace = fmt.colorSpace;
        sci.imageExtent = ext;
        sci.imageArrayLayers = 1;
        sci.imageUsage = VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT;
        sci.imageSharingMode = VK_SHARING_MODE_EXCLUSIVE;
        sci.preTransform = caps.currentTransform;
        sci.compositeAlpha = alpha;
        sci.presentMode = pm;
        sci.clipped = VK_TRUE;
        VkResult r = vk_.CreateSwapchainKHR(dev_, &sci, nullptr, &swapchain_);
        if (r != VK_SUCCESS) { Log("presenter: vkCreateSwapchainKHR %d", r); return false; }
        uint32_t ni = 0;
        vk_.GetSwapchainImagesKHR(dev_, swapchain_, &ni, nullptr);
        images_.resize(ni);
        vk_.GetSwapchainImagesKHR(dev_, swapchain_, &ni, images_.data());
        Log("presenter: swapchain %ux%u, format %d, %u images, %s", ext.width, ext.height, fmt.format, ni,
            pm == VK_PRESENT_MODE_MAILBOX_KHR ? "mailbox" : "fifo");
        return true;
    }

    int MemoryType(uint32_t bits) {
        for (uint32_t i = 0; i < mem_.memoryTypeCount; i++)
            if ((bits & (1u << i)) && (mem_.memoryTypes[i].propertyFlags & VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT)) return (int)i;
        for (uint32_t i = 0; i < mem_.memoryTypeCount; i++) if (bits & (1u << i)) return (int)i;
        return -1;
    }

    // Imports one of SteamVR's shared images with the parameters it was made with (ALVR's recipe):
    // optimal tiling, mutable format (which keeps it uncompressed), opaque fd, dedicated memory. UNORM so a
    // blit copies the bytes rather than decoding sRGB.
    void Import(const PendingImport &p) {
        Imported im{p.handle};
        im.width = p.width;
        im.height = p.height;
        // The same format list SteamVR's mutable sRGB images carry: with it Turnip keeps UBWC compression on,
        // and the import has to agree on that or it reads compressed data as plain pixels.
        const char *fl = getenv("DROIDDECK_VR_IMPORT_FORMAT_LIST");
        const bool formatList = !fl || strcmp(fl, "0") != 0;
        VkFormat formats[2] = {(VkFormat)p.format, UnormOf(p.format)};
        VkImageFormatListCreateInfo list = {VK_STRUCTURE_TYPE_IMAGE_FORMAT_LIST_CREATE_INFO, nullptr, 2, formats};
        VkExternalMemoryImageCreateInfo ext = {VK_STRUCTURE_TYPE_EXTERNAL_MEMORY_IMAGE_CREATE_INFO, formatList ? &list : nullptr,
                                               VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_FD_BIT};
        VkImageCreateInfo ici = {VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO, &ext, VK_IMAGE_CREATE_MUTABLE_FORMAT_BIT, VK_IMAGE_TYPE_2D,
                                 UnormOf(p.format), {p.width, p.height, 1}, 1, 1, VK_SAMPLE_COUNT_1_BIT, VK_IMAGE_TILING_OPTIMAL,
                                 VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_INPUT_ATTACHMENT_BIT,
                                 VK_SHARING_MODE_EXCLUSIVE, 0, nullptr, VK_IMAGE_LAYOUT_UNDEFINED};
        VkResult r = vk_.CreateImage(dev_, &ici, nullptr, &im.image);
        if (r != VK_SUCCESS) { Log("presenter: import image %d", r); close(p.fd); return; }
        VkMemoryRequirements req;
        vk_.GetImageMemoryRequirements(dev_, im.image, &req);
        VkImportMemoryFdInfoKHR imp = {VK_STRUCTURE_TYPE_IMPORT_MEMORY_FD_INFO_KHR, nullptr, VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_FD_BIT, p.fd};
        VkMemoryDedicatedAllocateInfo ded = {VK_STRUCTURE_TYPE_MEMORY_DEDICATED_ALLOCATE_INFO, &imp, im.image, VK_NULL_HANDLE};
        VkMemoryAllocateInfo mai = {VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO, &ded, req.size, (uint32_t)MemoryType(req.memoryTypeBits)};
        r = vk_.AllocateMemory(dev_, &mai, nullptr, &im.memory);
        if (r != VK_SUCCESS) {
            Log("presenter: import memory (fd %d, %llu bytes) %d", p.fd, (unsigned long long)req.size, r);
            close(p.fd);
            vk_.DestroyImage(dev_, im.image, nullptr);
            return;
        }
        vk_.BindImageMemory(dev_, im.image, im.memory, 0);
        textures_.push_back(im);
        Log("presenter: imported texture 0x%llx (%ux%u, format %u)", (unsigned long long)p.handle, p.width, p.height, p.format);
    }

    void Forget(uint64_t handle) {
        for (auto it = textures_.begin(); it != textures_.end(); ++it) {
            if (it->handle == handle) {
                vk_.DeviceWaitIdle(dev_);
                vk_.DestroyImage(dev_, it->image, nullptr);
                vk_.FreeMemory(dev_, it->memory, nullptr);
                textures_.erase(it);
                return;
            }
        }
    }

    const Imported *Find(uint64_t h) const {
        for (auto &t : textures_) if (t.handle == h) return &t;
        return nullptr;
    }

    static VkImageMemoryBarrier Barrier(VkImage img, VkImageLayout from, VkImageLayout to, VkAccessFlags src, VkAccessFlags dst,
                                        uint32_t srcQ = VK_QUEUE_FAMILY_IGNORED, uint32_t dstQ = VK_QUEUE_FAMILY_IGNORED) {
        VkImageMemoryBarrier b = {VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER, nullptr, src, dst, from, to, srcQ, dstQ, img,
                                  {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1}};
        return b;
    }

    bool Draw(const EyeFrame &f) {
        const Imported *eye[2] = {Find(f.texture[0]), Find(f.texture[1])};
        if (!eye[0] || !eye[1]) return false;
        vk_.WaitForFences(dev_, 1, &fence_, VK_TRUE, UINT64_MAX);
        uint32_t idx = 0;
        VkResult r = vk_.AcquireNextImageKHR(dev_, swapchain_, 100000000ull, acquired_, VK_NULL_HANDLE, &idx);
        if (r != VK_SUCCESS && r != VK_SUBOPTIMAL_KHR) {
            static int said;
            if (said++ < 3) Log("presenter: acquire %d", r);
            return false;
        }
        vk_.ResetFences(dev_, 1, &fence_);
        vk_.ResetCommandBuffer(cmd_, 0);
        VkCommandBufferBeginInfo bi = {VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO, nullptr, VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT};
        vk_.BeginCommandBuffer(cmd_, &bi);
        VkImage dst = images_[idx];
        // The eye textures come from SteamVR's compositor (another process): take them from the external
        // queue family as they are (GENERAL keeps the contents; they are uncompressed).
        VkImageMemoryBarrier pre[3] = {
            Barrier(dst, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 0, VK_ACCESS_TRANSFER_WRITE_BIT),
            Barrier(eye[0]->image, VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_GENERAL, 0, VK_ACCESS_TRANSFER_READ_BIT,
                    VK_QUEUE_FAMILY_EXTERNAL, qfam_),
            Barrier(eye[1]->image, VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_GENERAL, 0, VK_ACCESS_TRANSFER_READ_BIT,
                    VK_QUEUE_FAMILY_EXTERNAL, qfam_)};
        vk_.CmdPipelineBarrier(cmd_, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, 0, 0, nullptr, 0, nullptr,
                               eye[0] == eye[1] ? 2 : 3, pre);
        VkClearColorValue black = {{0, 0, 0, 1}};
        VkImageSubresourceRange range = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
        vk_.CmdClearColorImage(cmd_, dst, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, &black, 1, &range);
        VkMemoryBarrier mb = {VK_STRUCTURE_TYPE_MEMORY_BARRIER, nullptr, VK_ACCESS_TRANSFER_WRITE_BIT, VK_ACCESS_TRANSFER_WRITE_BIT};
        vk_.CmdPipelineBarrier(cmd_, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, 0, 1, &mb, 0, nullptr, 0, nullptr);
        const int32_t half = (int32_t)extent_.width / 2;
        for (int e = 0; e < 2; e++) {
            const float *b = f.bounds[e];
            VkImageBlit blit = {};
            blit.srcSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1};
            blit.srcOffsets[0] = {(int32_t)(b[0] * eye[e]->width), (int32_t)(b[1] * eye[e]->height), 0};
            blit.srcOffsets[1] = {(int32_t)(b[2] * eye[e]->width), (int32_t)(b[3] * eye[e]->height), 1};
            blit.dstSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1};
            blit.dstOffsets[0] = {e * half, 0, 0};
            blit.dstOffsets[1] = {e * half + half, (int32_t)extent_.height, 1};
            vk_.CmdBlitImage(cmd_, eye[e]->image, VK_IMAGE_LAYOUT_GENERAL, dst, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &blit,
                             VK_FILTER_LINEAR);
        }
        VkImageMemoryBarrier post[3] = {
            Barrier(dst, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_PRESENT_SRC_KHR, VK_ACCESS_TRANSFER_WRITE_BIT, 0),
            Barrier(eye[0]->image, VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_GENERAL, VK_ACCESS_TRANSFER_READ_BIT, 0, qfam_,
                    VK_QUEUE_FAMILY_EXTERNAL),
            Barrier(eye[1]->image, VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_GENERAL, VK_ACCESS_TRANSFER_READ_BIT, 0, qfam_,
                    VK_QUEUE_FAMILY_EXTERNAL)};
        vk_.CmdPipelineBarrier(cmd_, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, 0, 0, nullptr, 0, nullptr,
                               eye[0] == eye[1] ? 2 : 3, post);
        vk_.EndCommandBuffer(cmd_);
        VkPipelineStageFlags wait = VK_PIPELINE_STAGE_TRANSFER_BIT;
        VkSubmitInfo si = {VK_STRUCTURE_TYPE_SUBMIT_INFO, nullptr, 1, &acquired_, &wait, 1, &cmd_, 1, &rendered_};
        if ((r = vk_.QueueSubmit(queue_, 1, &si, fence_)) != VK_SUCCESS) { Log("presenter: submit %d", r); return false; }
        VkPresentInfoKHR pi = {VK_STRUCTURE_TYPE_PRESENT_INFO_KHR, nullptr, 1, &rendered_, 1, &swapchain_, &idx, nullptr};
        r = vk_.QueuePresentKHR(queue_, &pi);
        if (r != VK_SUCCESS && r != VK_SUBOPTIMAL_KHR) {
            static int said;
            if (said++ < 3) Log("presenter: present %d", r);
        }
        return true;
    }

    std::mutex mutex_;
    std::condition_variable cv_;
    std::vector<PendingImport> pending_;
    std::vector<uint64_t> removals_;
    EyeFrame frame_{};
    std::chrono::steady_clock::time_point frameAt_{};
    bool haveFrame_ = false, exit_ = false, started_ = false;
    std::atomic<uint64_t> submitted_{0};
    std::thread thread_;

    X11 x_;
    Display *dpy_ = nullptr;
    Window win_ = 0;
    uint32_t width_ = 0, height_ = 0;
    Vk vk_;
    VkInstance inst_ = VK_NULL_HANDLE;
    VkPhysicalDevice pd_ = VK_NULL_HANDLE;
    VkPhysicalDeviceMemoryProperties mem_{};
    VkSurfaceKHR surface_ = VK_NULL_HANDLE;
    uint32_t qfam_ = 0;
    VkDevice dev_ = VK_NULL_HANDLE;
    VkQueue queue_ = VK_NULL_HANDLE;
    VkCommandPool pool_ = VK_NULL_HANDLE;
    VkCommandBuffer cmd_ = VK_NULL_HANDLE;
    VkFence fence_ = VK_NULL_HANDLE;
    VkSemaphore acquired_ = VK_NULL_HANDLE, rendered_ = VK_NULL_HANDLE;
    VkSwapchainKHR swapchain_ = VK_NULL_HANDLE;
    VkExtent2D extent_{};
    std::vector<VkImage> images_;
    std::vector<Imported> textures_;
};

} // namespace

void Presenter::AddTextures(const uint64_t h[3], const int fds[3], uint32_t f, uint32_t w, uint32_t hh) { Impl::Get().Add(h, fds, f, w, hh); }
void Presenter::RemoveTexture(uint64_t h) { Impl::Get().Remove(h); }
void Presenter::Submit(const EyeFrame &f) { Impl::Get().Submit(f); }
void Presenter::Shutdown() { Impl::Get().Shutdown(); }

} // namespace droiddeck
