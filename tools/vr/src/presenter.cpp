#define VK_NO_PROTOTYPES
#include <vulkan/vulkan_core.h>
typedef struct _XDisplay Display;
typedef unsigned long Window;
typedef unsigned long VisualID;
#include <vulkan/vulkan_xlib.h>
#include <wayland-client.h>
#include <vulkan/vulkan_wayland.h>
#include "xdg-shell-client-protocol.h"

#include "../../../app/src/main/cpp/waylandcomp/src/headpose_shm.h"
#include "presenter.h"
#include "presenter_image.h"
#include "present_sync.h"
#include "xr_tracking_file.h"
#include "xr_frame_channel.h"
#include "producer_lease.h"

#include <algorithm>
#include <atomic>
#include <chrono>
#include <condition_variable>
#include <cstdarg>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <dlfcn.h>
#include <fcntl.h>
#include <linux/futex.h>
#include <mutex>
#include <string>
#include <sys/mman.h>
#include <sys/syscall.h>
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
    if (vr::VRDriverContext() && vr::VRDriverLog()) vr::VRDriverLog()->Log(buf);
    fprintf(stderr, "[droiddeck] %s\n", buf);
}


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


#define VK_GLOBAL(X) X(CreateInstance)
#define VK_INSTANCE(X) \
    X(DestroyInstance) X(EnumeratePhysicalDevices) X(GetPhysicalDeviceQueueFamilyProperties) \
    X(GetPhysicalDeviceMemoryProperties) X(CreateDevice) X(GetDeviceProcAddr) X(CreateXlibSurfaceKHR) \
    X(CreateWaylandSurfaceKHR) \
    X(GetPhysicalDeviceSurfaceSupportKHR) X(GetPhysicalDeviceSurfaceCapabilitiesKHR) \
    X(GetPhysicalDeviceSurfaceFormatsKHR) X(GetPhysicalDeviceSurfacePresentModesKHR) X(GetPhysicalDeviceProperties) X(DestroySurfaceKHR)
#define VK_DEVICE(X) \
    X(GetDeviceQueue) X(CreateSwapchainKHR) X(DestroySwapchainKHR) X(GetSwapchainImagesKHR) X(AcquireNextImageKHR) \
    X(QueuePresentKHR) X(CreateCommandPool) X(AllocateCommandBuffers) X(BeginCommandBuffer) X(EndCommandBuffer) \
    X(ResetCommandBuffer) X(CmdPipelineBarrier) X(CmdBlitImage) X(CmdClearColorImage) X(QueueSubmit) X(CreateFence) \
    X(WaitForFences) X(ResetFences) X(CreateSemaphore) X(CreateImage) X(DestroyImage) X(GetImageMemoryRequirements) \
    X(AllocateMemory) X(FreeMemory) X(BindImageMemory) X(DeviceWaitIdle) X(DestroyDevice) X(DestroyFence) X(DestroySemaphore) \
    X(DestroyCommandPool) X(CreateBuffer) X(DestroyBuffer) X(GetBufferMemoryRequirements) X(BindBufferMemory) \
    X(MapMemory) X(UnmapMemory) X(InvalidateMappedMemoryRanges) X(CmdCopyImageToBuffer)

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
    uint32_t producerPid = 0;
    VkFormat format = VK_FORMAT_UNDEFINED;
};

struct PendingImport {
    uint64_t handle;
    int fd;
    uint32_t format, width, height, producerPid;
};

class CaptureLease {
public:
    explicit CaptureLease(uint32_t pid) : acquired_(droiddeck_producer_acquire(pid, &lease_)) {}
    ~CaptureLease() {
        if (lease_.descriptor < 0) return;
        if (gpuComplete_) droiddeck_producer_release(&lease_);
        else close(lease_.descriptor);
    }
    bool Acquired() const { return acquired_; }
    void BeginGpu() { gpuComplete_ = false; }
    void EndGpu() { gpuComplete_ = true; }
    bool Release() { return gpuComplete_ && droiddeck_producer_release(&lease_); }
private:
    droiddeck_producer_lease lease_{-1, 0};
    bool acquired_;
    bool gpuComplete_ = true;
};

class Impl {
public:
    static Impl &Get() {
        static Impl *impl = made_ = new Impl;
        return *impl;
    }

    static Impl *Made() { return made_; }

    void Add(const uint64_t handles[3], const int fds[3], uint32_t format, uint32_t w, uint32_t h, uint32_t producerPid) {
        std::lock_guard<std::mutex> lock(mutex_);
        if (failed_ || exit_) return;
        for (int i = 0; i < 3; i++) {
            if (fds[i] < 0) continue;
            int fd = dup(fds[i]);
            if (fd >= 0) pending_.push_back({handles[i], fd, format, w, h, producerPid});
        }
        EnsureThread();
        cv_.notify_all();
    }

    void Remove(uint64_t handle) {
        std::lock_guard<std::mutex> lock(mutex_);
        removals_.push_back(handle);
        cv_.notify_all();
    }

    void Submit(const EyeFrame &f) {
        std::unique_lock<std::mutex> lock(mutex_);
        if (failed_ || exit_) return;
        bool blocking = FrameChannelRequested();
        if (blocking) cv_.wait(lock, [this] { return failed_ || exit_ || (!haveFrame_ && !inFlight_); });
        if (failed_ || exit_) return;
        EnsureThread();
        frame_ = f;
        frameAt_ = std::chrono::steady_clock::now();
        const droiddeck_present_sync *sync = MapSync(frameAt_);
        const uint32_t issued = sync ? __atomic_load_n(&sync->issued, __ATOMIC_ACQUIRE) : 0;
        frameTracked_ = sync && issued != lastIssued_;
        frameMark_ = issued;
        lastIssued_ = issued;
        haveFrame_ = true;
        submitted_++;
        uint64_t ticket = ++ticket_;
        cv_.notify_all();
        if (blocking) cv_.wait(lock, [this, ticket] { return failed_ || completed_ >= ticket; });
    }

    void Shutdown() {
        {
            std::lock_guard<std::mutex> lock(mutex_);
            exit_ = true;
            cv_.notify_all();
        }
        if (thread_.joinable()) thread_.join();
    }

private:
    static bool FrameChannelRequested() {
        const char *mode = getenv("DROIDDECK_VR_PRESENT");
        return mode && !strcmp(mode, "questframes");
    }

    void Fail() {
        std::lock_guard<std::mutex> lock(mutex_);
        failed_ = true;
        for (const auto &pending : pending_) close(pending.fd);
        pending_.clear(); cv_.notify_all();
    }
    void EnsureThread() {
        if (!started_) {
            started_ = true;
            thread_ = std::thread([this] {
                try { Run(); }
                catch (const std::exception &error) { Log("presenter: %s", error.what()); }
                Cleanup(); Fail();
            });
        }
    }


    void Run() {
        const char *mode = getenv("DROIDDECK_VR_PRESENT");
        frameChannel_ = FrameChannelRequested();
        if (!frameChannel_) wayland_ = !(mode && !strcmp(mode, "x11")) && InitWayland();
        if (!frameChannel_ && !wayland_ && !InitWindow()) {
            Log("presenter: unavailable, frames are not shown");
            Fail(); return;
        }
        if (!InitVulkan()) {
            Log("presenter: unavailable, frames are not shown");
            Fail(); return;
        }
        if (const char *g = getenv("DROIDDECK_VR_PRESENT_GUARD_MS")) guard_ = std::chrono::microseconds((long)(atof(g) * 1000));
        uint64_t shown = 0, waited = 0, late = 0;
        std::chrono::steady_clock::duration longest{};
        auto lastLog = std::chrono::steady_clock::now();
        while (true) {
            EyeFrame f;
            uint64_t ticket = 0;
            std::chrono::steady_clock::time_point at;
            uint32_t mark = 0;
            bool tracked = false;
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
                mark = frameMark_;
                tracked = frameTracked_;
                bool draw = haveFrame_;
                haveFrame_ = false;
                inFlight_ = draw; ticket = ticket_;
                if (!draw) f.texture[0] = f.texture[1] = 0;
            }
            if (wl_) { wl_display_dispatch_pending(wl_); wl_display_flush(wl_); }
            for (auto &p : imports) Import(p);
            for (auto h : removals) Forget(h);
            if (f.texture[0] && !frameChannel_ && tracked) {
                const auto start = std::chrono::steady_clock::now();
                const int r = WaitDrawn(mark, start + kDrawnTimeout);
                if (r > 0) {
                    waited++;
                    longest = std::max(longest, std::chrono::steady_clock::now() - start);
                } else if (r < 0) {
                    late++;
                }
            } else if (f.texture[0] && !frameChannel_) {
                std::this_thread::sleep_until(at + guard_);
            }
            if (!frameChannel_) {
                if (const Imported *eye = Find(f.texture[0])) FollowPanel(eye->format);
            }
            if (f.texture[0] && (frameChannel_ ? Capture(f) : Draw(f))) shown++;
            {
                std::lock_guard<std::mutex> lock(mutex_);
                inFlight_ = false; completed_ = ticket; cv_.notify_all();
                if (failed_) break;
            }
            auto now = std::chrono::steady_clock::now();
            if (now - lastLog > std::chrono::seconds(10)) {
                Log("presenter: %llu frames shown, %llu submitted, %llu waited for the GPU (longest %.1f ms), %llu late (last 10 s)",
                    (unsigned long long)shown, (unsigned long long)submitted_.exchange(0), (unsigned long long)waited,
                    std::chrono::duration<double, std::milli>(longest).count(), (unsigned long long)late);
                shown = waited = late = 0;
                longest = {};
                lastLog = now;
            }
        }
    }

    void Cleanup() {
        if (dev_) vk_.DeviceWaitIdle(dev_);
        DestroyCapture();
        while (!textures_.empty()) Forget(textures_.back().handle);
        if (dev_) {
            if (swapchain_) vk_.DestroySwapchainKHR(dev_, swapchain_, nullptr);
            if (acquired_) vk_.DestroySemaphore(dev_, acquired_, nullptr);
            if (rendered_) vk_.DestroySemaphore(dev_, rendered_, nullptr);
            if (fence_) vk_.DestroyFence(dev_, fence_, nullptr);
            if (pool_) vk_.DestroyCommandPool(dev_, pool_, nullptr);
            vk_.DestroyDevice(dev_, nullptr); dev_ = VK_NULL_HANDLE;
        }
        if (surface_) vk_.DestroySurfaceKHR(inst_, surface_, nullptr);
        if (inst_) vk_.DestroyInstance(inst_, nullptr);
        surface_ = VK_NULL_HANDLE; inst_ = VK_NULL_HANDLE;
    }

    bool PanelSize(uint32_t *w, uint32_t *h) {
        if (!pose_) {
            int fd = open("/run/droiddeck/" DROIDDECK_HEADPOSE_FILE, O_RDONLY | O_CLOEXEC);
            if (fd < 0) return false;
            void *m = mmap(nullptr, sizeof(droiddeck_headpose), PROT_READ, MAP_SHARED, fd, 0);
            close(fd);
            if (m == MAP_FAILED) return false;
            pose_ = (const volatile droiddeck_headpose *)m;
        }
        for (int tries = 0; tries < 8; tries++) {
            const uint32_t a = __atomic_load_n(&pose_->seq, __ATOMIC_ACQUIRE);
            if (a & 1u) continue;
            const uint32_t ew = pose_->eye_w, eh = pose_->eye_h;
            __atomic_thread_fence(__ATOMIC_ACQUIRE);
            if (__atomic_load_n(&pose_->seq, __ATOMIC_RELAXED) != a) continue;
            if (!ew || !eh || pose_->magic != DROIDDECK_HEADPOSE_MAGIC) return false;
            *w = ew * 2;
            *h = eh;
            return true;
        }
        return false;
    }

    void FollowPanel(VkFormat eyeFormat) {
        uint32_t w = width_, h = height_;
        if (wayland_ && !PanelSize(&w, &h)) return;
        const bool resized = w != width_ || h != height_;
        const bool encodingChanged = SrgbEyeFormat(eyeFormat) != SrgbEyeFormat(eyeFormat_);
        const auto now = std::chrono::steady_clock::now();
        if (!resized && !encodingChanged && (swapchain_ || now < retryAt_)) return;
        vk_.DeviceWaitIdle(dev_);
        if (swapchain_) vk_.DestroySwapchainKHR(dev_, swapchain_, nullptr);
        swapchain_ = VK_NULL_HANDLE;
        images_.clear();
        if (resized) Log("presenter: panel now %ux%u (was %ux%u)", w, h, width_, height_);
        width_ = w;
        height_ = h;
        eyeFormat_ = eyeFormat;
        if (!CreateSwapchain()) retryAt_ = now + std::chrono::seconds(1);
    }

    std::chrono::microseconds guard_{4000};
    std::chrono::steady_clock::time_point retryAt_{};
    static constexpr std::chrono::milliseconds kDrawnTimeout{100};

    const droiddeck_present_sync *MapSync(std::chrono::steady_clock::time_point now) {
        if (sync_) return sync_;
        if (syncTried_.time_since_epoch().count() && now - syncTried_ < std::chrono::seconds(1)) return nullptr;
        syncTried_ = now;
        const char *rt = getenv("XDG_RUNTIME_DIR");
        std::string path = std::string(rt && *rt ? rt : "/run/droiddeck") + "/droiddeck-steamvr/" DROIDDECK_PRESENT_SYNC_NAME;
        int fd = open(path.c_str(), O_RDONLY | O_CLOEXEC);
        if (fd < 0) return nullptr;
        void *m = mmap(nullptr, DROIDDECK_PRESENT_SYNC_SIZE, PROT_READ, MAP_SHARED, fd, 0);
        close(fd);
        if (m == MAP_FAILED) return nullptr;
        sync_ = (const droiddeck_present_sync *)m;
        Log("presenter: frames wait for the compositor's GPU work (%s)", path.c_str());
        return sync_;
    }

    int WaitDrawn(uint32_t mark, std::chrono::steady_clock::time_point until) {
        const droiddeck_present_sync *s = sync_;
        int slept = 0;
        while (true) {
            const uint32_t done = __atomic_load_n(&s->done, __ATOMIC_ACQUIRE);
            if ((int32_t)(done - mark) >= 0) return slept;
            const auto left = until - std::chrono::steady_clock::now();
            if (left <= std::chrono::steady_clock::duration::zero()) return -1;
            const auto ns = std::chrono::duration_cast<std::chrono::nanoseconds>(left).count();
            struct timespec ts = {(time_t)(ns / 1000000000), (long)(ns % 1000000000)};
            syscall(SYS_futex, const_cast<uint32_t *>(&s->done), FUTEX_WAIT, done, &ts, nullptr, 0);
            slept = 1;
        }
    }

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
        width_ = height_ = 0;
        if (!PanelSize(&width_, &height_)) width_ = height_ = 0;
        XrTrackingFile xrFile;
        DroidDeckXrTracking tracking;
        if (xrFile.Read(&tracking) && XrTrackingFile::Fresh(tracking)) {
            width_ = tracking.eyes[0].width * 2; height_ = tracking.eyes[0].height;
        }
        if (!width_ && cfgW_ > 0 && cfgH_ > 0) { width_ = (uint32_t)cfgW_; height_ = (uint32_t)cfgH_; }
        if (!width_) { width_ = 1920; height_ = 1080; }
        Log("presenter: %ux%u fullscreen window on Wayland display %s (configured %s)", width_, height_, name, configured_ ? "yes" : "no");
        return true;
    }

    bool InitWindow() {
        if (!x_.Load()) { Log("presenter: no libX11"); return false; }
        const char *name = getenv("DROIDDECK_VR_DISPLAY");
        if (!name || !*name) name = ":0";
        dpy_ = x_.OpenDisplay(name);
        if (!dpy_) { Log("presenter: cannot open X display %s", name); return false; }
        int scr = x_.DefaultScreen(dpy_);
        width_ = (uint32_t)x_.DisplayWidth(dpy_, scr);
        height_ = (uint32_t)x_.DisplayHeight(dpy_, scr);
        win_ = x_.CreateSimpleWindow(dpy_, x_.DefaultRootWindow(dpy_), 0, 0, width_, height_, 0, 0, 0);
        x_.StoreName(dpy_, win_, "DroidDeck Cardboard VR");
        const char *atom = getenv("DROIDDECK_VR_OVERLAY_ATOM");
        if (!atom || !*atom) atom = "STEAM_OVERLAY";
        unsigned long cardinal = 6;
        unsigned long one = 1;
        x_.ChangeProperty(dpy_, win_, x_.InternAtom(dpy_, atom, 0), cardinal, 32, 0  ,
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
        VkApplicationInfo app = {VK_STRUCTURE_TYPE_APPLICATION_INFO, nullptr, "droiddeck-vr-presenter", 1, nullptr, 0, VK_API_VERSION_1_2};
        VkInstanceCreateInfo ici = {VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO, nullptr, 0, &app, 0, nullptr,
                                    frameChannel_ ? 0u : 2u, frameChannel_ ? nullptr : iexts};
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

        if (!frameChannel_ && wayland_) {
            VkWaylandSurfaceCreateInfoKHR wci = {VK_STRUCTURE_TYPE_WAYLAND_SURFACE_CREATE_INFO_KHR, nullptr, 0, wl_, wlSurf_};
            if ((r = vk_.CreateWaylandSurfaceKHR(inst_, &wci, nullptr, &surface_)) != VK_SUCCESS) { Log("presenter: wayland surface %d", r); return false; }
        } else if (!frameChannel_) {
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
            if (frameChannel_) present = VK_TRUE;
            else vk_.GetPhysicalDeviceSurfaceSupportKHR(pd_, i, surface_, &present);
            if ((qf[i].queueFlags & VK_QUEUE_GRAPHICS_BIT) && present) { qfam_ = i; break; }
        }
        if (qfam_ == UINT32_MAX) { Log("presenter: no queue can present"); return false; }
        float prio = 1.0f;
        VkDeviceQueueCreateInfo qci = {VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO, nullptr, 0, qfam_, 1, &prio};
        const char *dexts[] = {"VK_KHR_external_memory_fd", "VK_KHR_swapchain"};
        VkDeviceCreateInfo dci = {VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO, nullptr, 0, 1, &qci, 0, nullptr, frameChannel_ ? 1u : 2u, dexts, nullptr};
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
        VkSurfaceFormatKHR fmt{};
        if (!EyeSurfaceFormat(eyeFormat_, fmts.data(), nf, &fmt)) {
            Log("presenter: no surface format preserves eye format %d", eyeFormat_);
            return false;
        }
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
        if (r != VK_SUCCESS) {
            swapchain_ = VK_NULL_HANDLE;
            Log("presenter: vkCreateSwapchainKHR %d", r);
            return false;
        }
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

    void Import(const PendingImport &p) {
        Imported im{p.handle};
        im.width = p.width;
        im.height = p.height;
        im.producerPid = p.producerPid;
        im.format = (VkFormat)p.format;
        // Dedicated OPAQUE_FD imports must match SteamVR's exported image description.
        SharedEyeImage description(im.format, p.width, p.height);
        VkResult r = vk_.CreateImage(dev_, &description.info, nullptr, &im.image);
        if (r != VK_SUCCESS) { Log("presenter: import image %d", r); close(p.fd); return; }
        VkMemoryRequirements req;
        vk_.GetImageMemoryRequirements(dev_, im.image, &req);
        VkImportMemoryFdInfoKHR imp = {VK_STRUCTURE_TYPE_IMPORT_MEMORY_FD_INFO_KHR, nullptr, VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_FD_BIT, p.fd};
        VkMemoryDedicatedAllocateInfo ded = {VK_STRUCTURE_TYPE_MEMORY_DEDICATED_ALLOCATE_INFO, &imp, im.image, VK_NULL_HANDLE};
        int memoryType = MemoryType(req.memoryTypeBits);
        if (memoryType < 0) { vk_.DestroyImage(dev_, im.image, nullptr); close(p.fd); return; }
        VkMemoryAllocateInfo mai = {VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO, &ded, req.size, (uint32_t)memoryType};
        r = vk_.AllocateMemory(dev_, &mai, nullptr, &im.memory);
        if (r != VK_SUCCESS) {
            Log("presenter: import memory (fd %d, %llu bytes) %d", p.fd, (unsigned long long)req.size, r);
            close(p.fd);
            vk_.DestroyImage(dev_, im.image, nullptr);
            return;
        }
        r = vk_.BindImageMemory(dev_, im.image, im.memory, 0);
        if (r != VK_SUCCESS) {
            Log("presenter: bind imported image %d", r);
            vk_.DestroyImage(dev_, im.image, nullptr);
            vk_.FreeMemory(dev_, im.memory, nullptr);
            return;
        }
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

    void DestroyCapture() {
        if (!dev_) return;
        if (captureImage_) vk_.DestroyImage(dev_, captureImage_, nullptr);
        if (captureMemory_) vk_.FreeMemory(dev_, captureMemory_, nullptr);
        if (readbackBuffer_) vk_.DestroyBuffer(dev_, readbackBuffer_, nullptr);
        if (readbackMemory_) vk_.FreeMemory(dev_, readbackMemory_, nullptr);
        captureImage_ = VK_NULL_HANDLE; captureMemory_ = readbackMemory_ = VK_NULL_HANDLE;
        readbackBuffer_ = VK_NULL_HANDLE; captureWidth_ = captureHeight_ = 0;
    }

    bool PrepareCapture(const DroidDeckXrFrameHeader &header) {
        if (captureWidth_ == header.width && captureHeight_ == header.height) return true;
        DestroyCapture();
        auto fail = [this](VkResult result) { Log("presenter: capture allocation failed %d", result); DestroyCapture(); return false; };
        VkImageCreateInfo image{VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO};
        image.imageType = VK_IMAGE_TYPE_2D; image.format = VK_FORMAT_R8G8B8A8_UNORM;
        image.extent = {header.width, header.height, 1}; image.mipLevels = image.arrayLayers = 1;
        image.samples = VK_SAMPLE_COUNT_1_BIT; image.tiling = VK_IMAGE_TILING_OPTIMAL;
        image.usage = VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT;
        VkResult result = vk_.CreateImage(dev_, &image, nullptr, &captureImage_);
        if (result != VK_SUCCESS) return fail(result);
        VkMemoryRequirements requirements{}; vk_.GetImageMemoryRequirements(dev_, captureImage_, &requirements);
        int type = MemoryType(requirements.memoryTypeBits);
        if (type < 0) return fail(VK_ERROR_FEATURE_NOT_PRESENT);
        VkMemoryAllocateInfo allocation{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};
        allocation.allocationSize = requirements.size; allocation.memoryTypeIndex = type;
        result = vk_.AllocateMemory(dev_, &allocation, nullptr, &captureMemory_);
        if (result != VK_SUCCESS) return fail(result);
        result = vk_.BindImageMemory(dev_, captureImage_, captureMemory_, 0);
        if (result != VK_SUCCESS) return fail(result);
        VkBufferCreateInfo buffer{VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO}; buffer.size = header.bytes;
        buffer.usage = VK_BUFFER_USAGE_TRANSFER_DST_BIT;
        result = vk_.CreateBuffer(dev_, &buffer, nullptr, &readbackBuffer_);
        if (result != VK_SUCCESS) return fail(result);
        vk_.GetBufferMemoryRequirements(dev_, readbackBuffer_, &requirements);
        type = -1;
        for (uint32_t index = 0; index < mem_.memoryTypeCount; index++) {
            if ((requirements.memoryTypeBits & (1u << index)) && (mem_.memoryTypes[index].propertyFlags & VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT)) {
                type = index;
                if (mem_.memoryTypes[index].propertyFlags & VK_MEMORY_PROPERTY_HOST_COHERENT_BIT) break;
            }
        }
        if (type < 0) return fail(VK_ERROR_FEATURE_NOT_PRESENT);
        allocation.allocationSize = requirements.size; allocation.memoryTypeIndex = type;
        result = vk_.AllocateMemory(dev_, &allocation, nullptr, &readbackMemory_);
        if (result != VK_SUCCESS) return fail(result);
        result = vk_.BindBufferMemory(dev_, readbackBuffer_, readbackMemory_, 0);
        if (result != VK_SUCCESS) return fail(result);
        captureWidth_ = header.width; captureHeight_ = header.height;
        return true;
    }

    bool Capture(const EyeFrame &frame) {
        if (frame.layers != 1 || !xr_frame_valid(frame.render)) {
            static unsigned said = 0;
            if (said++ < 3) Log("presenter: refusing Quest frame with unsupported layer count or render geometry (%u layers)", frame.layers);
            return false;
        }
        const Imported *eye[2] = {Find(frame.texture[0]), Find(frame.texture[1])};
        if (!eye[0] || !eye[1]) return false;
        for (int index = 0; index < 2; index++) {
            for (float value : frame.bounds[index]) if (!std::isfinite(value) || value < 0 || value > 1) return false;
            if (frame.bounds[index][0] == frame.bounds[index][2] || frame.bounds[index][1] == frame.bounds[index][3]) return false;
        }
        if (!eye[0]->producerPid || eye[0]->producerPid != eye[1]->producerPid) return false;
        CaptureLease lease(eye[0]->producerPid);
        if (!lease.Acquired()) {
            static unsigned said = 0;
            if (said++ < 3) Log("presenter: refusing Quest frame without a completed, retained producer lease (pid %u)", eye[0]->producerPid);
            return false;
        }
        VkResult result = vk_.WaitForFences(dev_, 1, &fence_, VK_TRUE, UINT64_MAX);
        if (result != VK_SUCCESS) { Fail(); return false; }
        if (!PrepareCapture(frame.render)) return false;
        if (vk_.ResetCommandBuffer(cmd_, 0) != VK_SUCCESS) { Fail(); return false; }
        VkCommandBufferBeginInfo begin{VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO}; begin.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
        if (vk_.BeginCommandBuffer(cmd_, &begin) != VK_SUCCESS) { Fail(); return false; }
        VkImageMemoryBarrier before[] = {
            Barrier(captureImage_, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 0, VK_ACCESS_TRANSFER_WRITE_BIT),
            Barrier(eye[0]->image, VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_GENERAL, 0, VK_ACCESS_TRANSFER_READ_BIT, VK_QUEUE_FAMILY_EXTERNAL, qfam_),
            Barrier(eye[1]->image, VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_GENERAL, 0, VK_ACCESS_TRANSFER_READ_BIT, VK_QUEUE_FAMILY_EXTERNAL, qfam_)};
        vk_.CmdPipelineBarrier(cmd_, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, 0, 0, nullptr, 0, nullptr,
                               eye[0] == eye[1] ? 2 : 3, before);
        int32_t half = frame.render.width / 2;
        for (int index = 0; index < 2; index++) {
            const float *bounds = frame.bounds[index];
            VkImageBlit blit{}; blit.srcSubresource = blit.dstSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1};
            blit.srcOffsets[0] = {static_cast<int32_t>(bounds[0] * eye[index]->width), static_cast<int32_t>(bounds[1] * eye[index]->height), 0};
            blit.srcOffsets[1] = {static_cast<int32_t>(bounds[2] * eye[index]->width), static_cast<int32_t>(bounds[3] * eye[index]->height), 1};
            blit.dstOffsets[0] = {index * half, 0, 0}; blit.dstOffsets[1] = {(index + 1) * half, static_cast<int32_t>(frame.render.height), 1};
            vk_.CmdBlitImage(cmd_, eye[index]->image, VK_IMAGE_LAYOUT_GENERAL, captureImage_, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &blit, VK_FILTER_LINEAR);
        }
        VkImageMemoryBarrier after[] = {
            Barrier(captureImage_, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, VK_ACCESS_TRANSFER_WRITE_BIT, VK_ACCESS_TRANSFER_READ_BIT),
            Barrier(eye[0]->image, VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_GENERAL, VK_ACCESS_TRANSFER_READ_BIT, 0, qfam_, VK_QUEUE_FAMILY_EXTERNAL),
            Barrier(eye[1]->image, VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_GENERAL, VK_ACCESS_TRANSFER_READ_BIT, 0, qfam_, VK_QUEUE_FAMILY_EXTERNAL)};
        vk_.CmdPipelineBarrier(cmd_, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, 0, 0, nullptr, 0, nullptr,
                               eye[0] == eye[1] ? 2 : 3, after);
        VkBufferImageCopy copy{}; copy.imageSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1};
        copy.imageExtent = {frame.render.width, frame.render.height, 1};
        vk_.CmdCopyImageToBuffer(cmd_, captureImage_, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, readbackBuffer_, 1, &copy);
        VkBufferMemoryBarrier host{VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER};
        host.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT; host.dstAccessMask = VK_ACCESS_HOST_READ_BIT;
        host.srcQueueFamilyIndex = host.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        host.buffer = readbackBuffer_; host.size = VK_WHOLE_SIZE;
        vk_.CmdPipelineBarrier(cmd_, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_HOST_BIT, 0, 0, nullptr, 1, &host, 0, nullptr);
        if (vk_.EndCommandBuffer(cmd_) != VK_SUCCESS || vk_.ResetFences(dev_, 1, &fence_) != VK_SUCCESS) { Fail(); return false; }
        VkSubmitInfo submit{VK_STRUCTURE_TYPE_SUBMIT_INFO}; submit.commandBufferCount = 1; submit.pCommandBuffers = &cmd_;
        lease.BeginGpu();
        result = vk_.QueueSubmit(queue_, 1, &submit, fence_);
        if (result != VK_SUCCESS) {
            if (vk_.DeviceWaitIdle(dev_) == VK_SUCCESS) lease.EndGpu();
            Fail(); return false;
        }
        result = vk_.WaitForFences(dev_, 1, &fence_, VK_TRUE, UINT64_MAX);
        if (result != VK_SUCCESS) {
            if (vk_.DeviceWaitIdle(dev_) == VK_SUCCESS) lease.EndGpu();
            Fail(); return false;
        }
        lease.EndGpu();
        if (!lease.Release()) return false;
        void *mapping = nullptr;
        if (vk_.MapMemory(dev_, readbackMemory_, 0, VK_WHOLE_SIZE, 0, &mapping) != VK_SUCCESS) return false;
        VkMappedMemoryRange range{VK_STRUCTURE_TYPE_MAPPED_MEMORY_RANGE}; range.memory = readbackMemory_; range.size = VK_WHOLE_SIZE;
        result = vk_.InvalidateMappedMemoryRanges(dev_, 1, &range);
        bool sent = result == VK_SUCCESS && sender_.Send("/run/droiddeck/" DROIDDECK_XR_FRAME_SOCKET,
                                                       frame.render, static_cast<unsigned char *>(mapping));
        vk_.UnmapMemory(dev_, readbackMemory_);
        return sent;
    }

    bool Draw(const EyeFrame &f) {
        const Imported *eye[2] = {Find(f.texture[0]), Find(f.texture[1])};
        if (!eye[0] || !eye[1] || !swapchain_) return false;
        if (SrgbEyeFormat(eye[0]->format) != SrgbEyeFormat(eye[1]->format)) return false;
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
        if (vk_.WaitForFences(dev_, 1, &fence_, VK_TRUE, (uint64_t)std::chrono::nanoseconds(kDrawnTimeout).count()) == VK_TIMEOUT) {
            static int said;
            if (said++ < 3) Log("presenter: the copy took over %lld ms; showing it anyway", (long long)kDrawnTimeout.count());
        }
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
    uint32_t frameMark_ = 0, lastIssued_ = 0;
    bool frameTracked_ = false;
    const droiddeck_present_sync *sync_ = nullptr;
    const volatile droiddeck_headpose *pose_ = nullptr;
    std::chrono::steady_clock::time_point syncTried_{};
    bool haveFrame_ = false, exit_ = false, started_ = false, inFlight_ = false, failed_ = false;
    uint64_t ticket_ = 0, completed_ = 0;
    bool frameChannel_ = false;
    XrFrameSender sender_;
    VkImage captureImage_ = VK_NULL_HANDLE;
    VkDeviceMemory captureMemory_ = VK_NULL_HANDLE, readbackMemory_ = VK_NULL_HANDLE;
    VkBuffer readbackBuffer_ = VK_NULL_HANDLE;
    uint32_t captureWidth_ = 0, captureHeight_ = 0;
    std::atomic<uint64_t> submitted_{0};
    std::thread thread_;
    static inline std::atomic<Impl *> made_{nullptr};

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
    VkFormat eyeFormat_ = VK_FORMAT_UNDEFINED;
    VkExtent2D extent_{};
    std::vector<VkImage> images_;
    std::vector<Imported> textures_;
};

}

void Presenter::AddTextures(const uint64_t h[3], const int fds[3], uint32_t f, uint32_t w, uint32_t hh, uint32_t pid) { Impl::Get().Add(h, fds, f, w, hh, pid); }
void Presenter::RemoveTexture(uint64_t h) { Impl::Get().Remove(h); }
void Presenter::Submit(const EyeFrame &f) { Impl::Get().Submit(f); }
void Presenter::Shutdown() {
    if (Impl *impl = Impl::Made()) impl->Shutdown();
}

}
