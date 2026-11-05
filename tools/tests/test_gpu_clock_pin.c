#include <assert.h>
#include <errno.h>
#include <pthread.h>
#include <stdarg.h>
#include <stdatomic.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <sys/mman.h>
#include <unistd.h>

#include "../../app/src/main/cpp/adrenotools/src/hook/kgsl.h"
#include "../../app/src/main/cpp/waylandcomp/src/gpu_clock_pin.h"

enum { NONE, OPEN, DEVICE, CONTEXT, CONSTRAINT, MEMORY, MAP, SYNC, SUBMIT, THREAD, WAIT };
static atomic_int failure, descriptors, contexts, memories, mappings, submissions, waits;
static atomic_bool timeout_wait;
static bool legacy_core;
static uint32_t commands[1024];

int __android_log_print(int priority, const char *tag, const char *format, ...) {
    (void)priority; (void)tag; (void)format;
    return 0;
}

int __wrap_open(const char *path, int flags, ...) {
    (void)flags;
    assert(!strcmp(path, "/dev/kgsl-3d0"));
    if (atomic_load(&failure) == OPEN) { errno = EACCES; return -1; }
    assert(atomic_fetch_add(&descriptors, 1) == 0);
    return 91;
}

int __wrap_close(int fd) {
    assert(fd == 91);
    assert(atomic_fetch_sub(&descriptors, 1) == 1);
    errno = EBADF;
    return 0;
}

void *__wrap_mmap(void *address, size_t size, int protection, int flags, int fd, off_t offset) {
    (void)address; (void)protection; (void)flags;
    assert(fd == 91 && size == sizeof(commands) && offset == (23 << 12));
    if (atomic_load(&failure) == MAP) { errno = EACCES; return MAP_FAILED; }
    assert(atomic_fetch_add(&mappings, 1) == 0);
    return commands;
}

int __wrap_munmap(void *address, size_t size) {
    assert(address == commands && size == sizeof(commands));
    assert(atomic_fetch_sub(&mappings, 1) == 1);
    return 0;
}

#ifdef __ANDROID__
int __wrap_ioctl(int fd, int request, ...) {
#else
int __wrap_ioctl(int fd, unsigned long request, ...) {
#endif
    assert(fd == 91);
    va_list args;
    va_start(args, request);
    void *arg = va_arg(args, void *);
    va_end(args);
    int step = NONE;
    switch ((unsigned int)request) {
    case IOCTL_KGSL_DEVICE_GETPROPERTY: step = DEVICE; break;
    case IOCTL_KGSL_DRAWCTXT_CREATE: step = CONTEXT; break;
    case IOCTL_KGSL_SETPROPERTY: step = CONSTRAINT; break;
    case IOCTL_KGSL_GPUMEM_ALLOC_ID: step = MEMORY; break;
    case IOCTL_KGSL_GPUMEM_SYNC_CACHE: step = SYNC; break;
    case IOCTL_KGSL_GPU_COMMAND: step = SUBMIT; break;
    case IOCTL_KGSL_DEVICE_WAITTIMESTAMP_CTXTID: step = WAIT; break;
    }
    if (step && atomic_load(&failure) == step) { errno = EACCES; return -1; }
    switch ((unsigned int)request) {
    case IOCTL_KGSL_DEVICE_GETPROPERTY: {
        struct kgsl_device_getproperty *property = arg;
        assert(property->type == KGSL_PROP_DEVICE_INFO);
        ((struct kgsl_devinfo *)property->value)->chip_id = legacy_core ? 0x04030000 : 0x07030000;
        break;
    }
    case IOCTL_KGSL_DRAWCTXT_CREATE: {
        struct kgsl_drawctxt_create *context = arg;
        assert(context->flags & KGSL_CONTEXT_PWR_CONSTRAINT);
        context->drawctxt_id = 17;
        atomic_fetch_add(&contexts, 1);
        break;
    }
    case IOCTL_KGSL_SETPROPERTY: {
        struct kgsl_device_getproperty *property = arg;
        assert(property->type == KGSL_PROP_PWR_CONSTRAINT);
        struct kgsl_device_constraint *constraint = property->value;
        assert(constraint->context_id == 17 && constraint->type == KGSL_CONSTRAINT_PWRLEVEL);
        assert(((struct kgsl_device_constraint_pwrlevel *)constraint->data)->level == KGSL_CONSTRAINT_PWR_MAX);
        break;
    }
    case IOCTL_KGSL_GPUMEM_ALLOC_ID: {
        struct kgsl_gpumem_alloc_id *memory = arg;
        memory->id = 23; memory->gpuaddr = 0x100000; memory->mmapsize = sizeof(commands);
        atomic_fetch_add(&memories, 1);
        break;
    }
    case IOCTL_KGSL_GPUMEM_SYNC_CACHE:
        assert(((struct kgsl_gpumem_sync_cache *)arg)->op == KGSL_GPUMEM_CACHE_CLEAN);
        break;
    case IOCTL_KGSL_GPU_COMMAND: {
        struct kgsl_gpu_command *command = arg;
        struct kgsl_command_object *object = (void *)(uintptr_t)command->cmdlist;
        assert(command->context_id == 17 && command->numcmds == 1);
        assert(command->flags & KGSL_CMDBATCH_PWR_CONSTRAINT);
        assert(object->id == 23 && object->gpuaddr == 0x100000 && object->size == 8);
        assert(commands[0] == (legacy_core ? 0xc0001000 : 0x70100001) && commands[1] == 0);
        command->timestamp = atomic_fetch_add(&submissions, 1) + 1;
        break;
    }
    case IOCTL_KGSL_DEVICE_WAITTIMESTAMP_CTXTID:
        atomic_fetch_add(&waits, 1);
        if (atomic_load(&timeout_wait)) { errno = ETIMEDOUT; return -1; }
        break;
    case IOCTL_KGSL_DRAWCTXT_DESTROY:
        assert(atomic_fetch_sub(&contexts, 1) == 1);
        break;
    case IOCTL_KGSL_GPUMEM_FREE_ID:
        assert(atomic_fetch_sub(&memories, 1) == 1);
        break;
    default: assert(!"unexpected ioctl");
    }
    return 0;
}

int __real_pthread_create(pthread_t *, const pthread_attr_t *, void *(*)(void *), void *);
int __wrap_pthread_create(pthread_t *thread, const pthread_attr_t *attr, void *(*start)(void *), void *arg) {
    if (atomic_load(&failure) == THREAD) return EAGAIN;
    return __real_pthread_create(thread, attr, start, arg);
}

static void assert_released(void) {
    assert(!atomic_load(&descriptors) && !atomic_load(&contexts));
    assert(!atomic_load(&memories) && !atomic_load(&mappings));
}

int main(void) {
    assert(gpu_clock_pin_set(false));
    assert_released();
    legacy_core = true;
    assert(gpu_clock_pin_set(true));
    assert(gpu_clock_pin_set(false));
    assert_released();
    legacy_core = false;
    for (int step = OPEN; step <= THREAD; ++step) {
        atomic_store(&failure, step);
        assert(!gpu_clock_pin_set(true));
        assert(errno == (step == THREAD ? EAGAIN : EACCES));
        assert_released();
    }
    atomic_store(&failure, NONE);
    atomic_store(&submissions, 0);
    assert(gpu_clock_pin_set(true));
    assert(gpu_clock_pin_set(true));
    for (int i = 0; i < 100 && atomic_load(&submissions) < 2; ++i) usleep(10000);
    assert(atomic_load(&submissions) > 1);
    assert(gpu_clock_pin_set(false));
    assert(gpu_clock_pin_set(false));
    assert_released();

    atomic_store(&timeout_wait, true);
    atomic_store(&submissions, 0);
    atomic_store(&waits, 0);
    assert(gpu_clock_pin_set(true));
    for (int i = 0; i < 100 && atomic_load(&waits) < 2; ++i) usleep(10000);
    assert(atomic_load(&waits) >= 2);
    assert(atomic_load(&submissions) == 1);
    assert(gpu_clock_pin_set(false));
    assert_released();
    atomic_store(&timeout_wait, false);

    atomic_store(&failure, WAIT);
    assert(gpu_clock_pin_set(true));
    for (int i = 0; i < 100 && atomic_load(&descriptors); ++i) usleep(10000);
    assert_released();
    atomic_store(&failure, NONE);
    assert(gpu_clock_pin_set(true));
    assert(gpu_clock_pin_set(false));
    assert_released();
    puts("gpu clock pin: failure cleanup, renewal, bounded queue and restart passed");
    return 0;
}
