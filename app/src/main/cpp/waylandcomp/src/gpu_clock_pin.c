#include "gpu_clock_pin.h"

#include <android/log.h>
#include <errno.h>
#include <fcntl.h>
#include <pthread.h>
#include <stdatomic.h>
#include <stdint.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/mman.h>
#include <unistd.h>

#include "../../adrenotools/src/hook/kgsl.h"

static pthread_mutex_t lock = PTHREAD_MUTEX_INITIALIZER;
static pthread_t worker;
static bool joinable;
static atomic_bool running;

struct pin {
    int fd;
    unsigned int context;
    unsigned int memory;
    unsigned int timestamp;
    unsigned long gpuaddr;
    uint32_t nop;
    void *commands;
    size_t size;
};

static struct pin pin = { .fd = -1 };

static void release_pin(void) {
    if (pin.context) {
        struct kgsl_drawctxt_destroy destroy = { .drawctxt_id = pin.context };
        ioctl(pin.fd, IOCTL_KGSL_DRAWCTXT_DESTROY, &destroy);
    }
    if (pin.commands) munmap(pin.commands, pin.size);
    if (pin.memory) {
        struct kgsl_gpumem_free_id free_memory = { .id = pin.memory };
        ioctl(pin.fd, IOCTL_KGSL_GPUMEM_FREE_ID, &free_memory);
    }
    if (pin.fd >= 0) close(pin.fd);
    pin = (struct pin) { .fd = -1 };
}

static bool submit_pin(void) {
    struct kgsl_command_object object = {
        .gpuaddr = pin.gpuaddr,
        .size = 2 * sizeof(uint32_t),
        .flags = KGSL_CMDLIST_IB,
        .id = pin.memory,
    };
    struct kgsl_gpu_command command = {
        .flags = KGSL_CMDBATCH_PWR_CONSTRAINT,
        .cmdlist = (uintptr_t)&object,
        .cmdsize = sizeof(object),
        .numcmds = 1,
        .context_id = pin.context,
    };
    if (ioctl(pin.fd, IOCTL_KGSL_GPU_COMMAND, &command) < 0) return false;
    pin.timestamp = command.timestamp;
    return true;
}

static bool prepare_pin(void) {
    pin.fd = open("/dev/kgsl-3d0", O_RDWR | O_CLOEXEC);
    if (pin.fd < 0) return false;
    struct kgsl_devinfo info = {0};
    struct kgsl_device_getproperty device_info = {
        .type = KGSL_PROP_DEVICE_INFO, .value = &info, .sizebytes = sizeof(info),
    };
    if (ioctl(pin.fd, IOCTL_KGSL_DEVICE_GETPROPERTY, &device_info) < 0) return false;
    unsigned int core = info.chip_id >> 24;
    if (!core) core = info.gpu_id / 100;
    if (core < 3) { errno = ENODEV; return false; }
    pin.nop = core >= 5 ? 0x70100001 : 0xc0001000;
    struct kgsl_drawctxt_create context = {
        .flags = KGSL_CONTEXT_NO_GMEM_ALLOC | KGSL_CONTEXT_PREAMBLE | KGSL_CONTEXT_PWR_CONSTRAINT,
    };
    if (ioctl(pin.fd, IOCTL_KGSL_DRAWCTXT_CREATE, &context) < 0) return false;
    pin.context = context.drawctxt_id;
    struct kgsl_device_constraint_pwrlevel level = { .level = KGSL_CONSTRAINT_PWR_MAX };
    struct kgsl_device_constraint constraint = {
        .type = KGSL_CONSTRAINT_PWRLEVEL,
        .context_id = pin.context,
        .data = &level,
        .size = sizeof(level),
    };
    struct kgsl_device_getproperty property = {
        .type = KGSL_PROP_PWR_CONSTRAINT,
        .value = &constraint,
        .sizebytes = sizeof(constraint),
    };
    if (ioctl(pin.fd, IOCTL_KGSL_SETPROPERTY, &property) < 0) return false;
    struct kgsl_gpumem_alloc_id memory = { .size = 4096 };
    if (ioctl(pin.fd, IOCTL_KGSL_GPUMEM_ALLOC_ID, &memory) < 0) return false;
    pin.memory = memory.id;
    pin.gpuaddr = memory.gpuaddr;
    pin.size = memory.mmapsize;
    void *commands = mmap(NULL, pin.size, PROT_READ | PROT_WRITE, MAP_SHARED, pin.fd,
                          (off_t)pin.memory << 12);
    if (commands == MAP_FAILED) return false;
    pin.commands = commands;
    // A3xx/A4xx use type-3 CP_NOP; newer cores use type-7 with zero odd-parity bits.
    uint32_t *words = commands;
    words[0] = pin.nop;
    words[1] = 0;
    struct kgsl_gpumem_sync_cache sync = { .id = pin.memory, .op = KGSL_GPUMEM_CACHE_CLEAN };
    if (ioctl(pin.fd, IOCTL_KGSL_GPUMEM_SYNC_CACHE, &sync) < 0) return false;
    return submit_pin();
}

static void *hold_pin(void *unused) {
    (void)unused;
    while (atomic_load(&running)) {
        struct kgsl_device_waittimestamp_ctxtid wait = {
            .context_id = pin.context,
            .timestamp = pin.timestamp,
            .timeout = 100,
        };
        if (ioctl(pin.fd, IOCTL_KGSL_DEVICE_WAITTIMESTAMP_CTXTID, &wait) < 0) {
            if (errno == EINTR || errno == ETIMEDOUT) continue;
            break;
        }
        // Constraints expire after idle_timer (normally 80 ms); renew with a NOP, keeping devfreq alive.
        usleep(25000);
        if (atomic_load(&running) && !submit_pin()) break;
    }
    if (atomic_exchange(&running, false))
        __android_log_print(ANDROID_LOG_WARN, "GpuClockPin", "constraint stopped: %s", strerror(errno));
    release_pin();
    return NULL;
}

bool gpu_clock_pin_set(bool on) {
    pthread_mutex_lock(&lock);
    if (joinable) {
        if (on && atomic_load(&running)) {
            pthread_mutex_unlock(&lock);
            return true;
        }
        atomic_store(&running, false);
        pthread_join(worker, NULL);
        joinable = false;
    }
    bool ok = true;
    if (on) {
        ok = prepare_pin();
        if (ok) {
            atomic_store(&running, true);
            int error = pthread_create(&worker, NULL, hold_pin, NULL);
            if (error) {
                atomic_store(&running, false);
                errno = error;
                ok = false;
            } else {
                joinable = true;
            }
        }
        if (!ok) {
            int error = errno;
            release_pin();
            errno = error;
        }
    }
    pthread_mutex_unlock(&lock);
    return ok;
}
