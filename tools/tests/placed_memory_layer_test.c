/* SPDX-License-Identifier: MIT
 * Stub-driver tests compile the actual patched placed-memory layer.
 */
#define _GNU_SOURCE
#include <assert.h>
#include <dlfcn.h>
#include <errno.h>
#include <inttypes.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/sysmacros.h>
#include <sys/mman.h>
#include <unistd.h>
static int fail_fixed_map, fail_reserve_map, layer_closes;
static int have_mapping_begin = 1, have_mapping_end = 1, fallback_mapping_api;
static int mapping_lock_depth, fail_mapping_begin, mapping_attempt;
static int replace_target_on_begin;
static unsigned mapping_begins, mapping_ends;
static void replace_target_during_begin(void);
static int mock_mapping_begin(void) {
    if (fail_mapping_begin) { fail_mapping_begin = 0; return EBUSY; }
    mapping_lock_depth++;
    mapping_begins++;
    if (replace_target_on_begin) {
        replace_target_on_begin = 0;
        replace_target_during_begin();
    }
    return 0;
}
static void mock_mapping_end(void) {
    assert(mapping_lock_depth > 0);
    mapping_lock_depth--;
    mapping_ends++;
}
static void *test_dlopen(const char *name, int flags) {
    assert(!strcmp(name, "libhybris-common.so.1") && (flags & RTLD_NOLOAD));
    return (void *)0x1234;
}
static void *test_dlsym(void *handle, const char *name) {
    assert(handle == RTLD_DEFAULT || handle == (void *)0x1234);
    if (fallback_mapping_api && handle == RTLD_DEFAULT) return NULL;
    if (!strcmp(name, "hybris_mali_mapping_begin"))
        return have_mapping_begin ? (void *)mock_mapping_begin : NULL;
    if (!strcmp(name, "hybris_mali_mapping_end"))
        return have_mapping_end ? (void *)mock_mapping_end : NULL;
    assert(!"unexpected dynamic symbol lookup");
    return NULL;
}
static void assert_mapping_transaction(void) {
    if (!mapping_attempt) return;
    assert(mapping_lock_depth == 1);
    /* Mapping hooks can re-enter the common lock on this same thread. The
     * separate common-hook suite verifies the real recursive mutex itself. */
    assert(!mock_mapping_begin() && mapping_lock_depth == 2);
    mock_mapping_end();
    assert(mapping_lock_depth == 1);
}
/* Model kbase's top-down, NULL-address-only mmap selection. Never reserve the
 * host's address space merely to test the guard algorithm. Native allocations
 * still use real memfd mappings below, outside these layer-only wrappers. */
enum vma_kind { RESERVATION, UNRELATED, GUARD, ALIAS, PROBE };
struct modeled_vma { uintptr_t start, end; enum vma_kind kind; int protection; };
static struct modeled_vma modeled[64];
static unsigned modeled_count;
static int model_enabled, model_gpu_fd = -1, model_extra_fd = -1;
static void *model_native;
static size_t model_length;
static const uintptr_t model_target = 0x21010000, model_limit = 0x6000000000ull;
static const off_t model_offset = 0x5000000000ull;
static int model_released, fail_guard, fail_chunk, wrong_chunk, race_restore;
static int model_target_protection, model_target_shared, model_target_file;
static unsigned guard_calls, chunk_calls;
static off_t chunk_offsets[16];
static size_t chunk_lengths[16];
static int modeled_fd(int fd) {
    struct stat a, b;
    return model_enabled && model_gpu_fd >= 0 && !fstat(fd, &a) &&
           !fstat(model_gpu_fd, &b) && a.st_dev == b.st_dev && a.st_ino == b.st_ino;
}
static void model_add(uintptr_t start, size_t length, enum vma_kind kind) {
    assert(length && start <= UINTPTR_MAX - length && modeled_count < 64);
    unsigned at = 0;
    while (at < modeled_count && modeled[at].start < start)
        at++;
    assert(!at || modeled[at - 1].end <= start);
    assert(at == modeled_count || start + length <= modeled[at].start);
    memmove(&modeled[at + 1], &modeled[at], (modeled_count - at) * sizeof(modeled[0]));
    int protection = kind == RESERVATION ? model_target_protection :
                     kind == GUARD ? PROT_NONE : PROT_READ | PROT_WRITE;
    modeled[at] = (struct modeled_vma){start, start + length, kind, protection};
    modeled_count++;
}
static void replace_target_during_begin(void) {
    for (unsigned i = 0; i < modeled_count; i++)
        if (modeled[i].start == model_target && modeled[i].end == model_target + model_length &&
            modeled[i].kind == RESERVATION) {
            modeled[i].kind = UNRELATED;
            return;
        }
    assert(!"target reservation missing during lock acquisition");
}
static int model_overlap(uintptr_t start, size_t length) {
    for (unsigned i = 0; i < modeled_count; i++)
        if (start < modeled[i].end && modeled[i].start < start + length)
            return 1;
    return 0;
}
static int test_munmap(void *pointer, size_t length) {
    uintptr_t start = (uintptr_t)pointer, end = start + length;
    if (model_enabled && model_native) {
        uintptr_t original = (uintptr_t)model_native;
        /* Only the native UnmapMemory callback may release this mapping. */
        assert(end <= original || start >= original + model_length);
    }
    if (!model_enabled || start < 2 * 1024 * 1024 || end > model_limit)
        return munmap(pointer, length);
    assert_mapping_transaction();
    for (unsigned i = 0; i < modeled_count;) {
        struct modeled_vma old = modeled[i];
        if (old.end <= start || old.start >= end) { i++; continue; }
        /* Any unmap of unrelated address space is an ownership violation. */
        assert(old.kind != UNRELATED);
        assert(start <= old.start && end >= old.end);
        memmove(&modeled[i], &modeled[i + 1], (--modeled_count - i) * sizeof(modeled[0]));
    }
    if (start == model_target && length == model_length)
        model_released = 1;
    return 0;
}
static void *test_mmap(void *a, size_t n, int p, int flags, int fd, off_t o) {
    if (model_enabled && fd < 0 && (flags & (MAP_FIXED | MAP_FIXED_NOREPLACE))) {
        assert_mapping_transaction();
        uintptr_t start = (uintptr_t)a;
        assert(flags & MAP_ANONYMOUS);
        assert(start == model_target || p == PROT_NONE);
        if (start != model_target && ++guard_calls == (unsigned)fail_guard) {
            errno = ENOMEM;
            return MAP_FAILED;
        }
        if ((flags & MAP_FIXED_NOREPLACE) && model_overlap(start, n)) {
            errno = EEXIST;
            return MAP_FAILED;
        }
        if (flags & MAP_FIXED) {
            if (fail_reserve_map) { errno = ENOMEM; return MAP_FAILED; }
            assert(!test_munmap(a, n));
        }
        model_add(start, n, start == model_target ? RESERVATION : GUARD);
        for (unsigned i = 0; i < modeled_count; i++)
            if (modeled[i].start == start) modeled[i].protection = p;
        return a;
    }
    if (modeled_fd(fd)) {
        assert_mapping_transaction();
        assert(!a && flags == MAP_SHARED && p == (PROT_READ | PROT_WRITE));
        if (model_released) {
            assert(chunk_calls < 16);
            chunk_offsets[chunk_calls] = o;
            chunk_lengths[chunk_calls++] = n;
            if (chunk_calls == (unsigned)fail_chunk) {
                if (race_restore) model_add(model_target, 4096, UNRELATED);
                errno = ENOMEM;
                return MAP_FAILED;
            }
            if (chunk_calls == (unsigned)wrong_chunk) {
                model_add(0x10000000, n, ALIAS);
                return (void *)0x10000000;
            }
        }
        uintptr_t top = model_limit;
        for (unsigned i = modeled_count; i; i--) {
            struct modeled_vma v = modeled[i - 1];
            if (v.start >= top) continue;
            if (v.end <= top && top - v.end >= n) break;
            top = v.start;
        }
        assert(top >= 2 * 1024 * 1024 + n);
        model_add(top - n, n, model_released ? ALIAS : PROBE);
        return (void *)(top - n);
    }
    if ((fail_fixed_map && fd >= 0 && (flags & MAP_FIXED)) ||
        (fail_reserve_map && (flags & MAP_FIXED) && p == PROT_NONE)) {
        errno = ENOMEM;
        return MAP_FAILED;
    }
    return mmap(a, n, p, flags, fd, o);
}
static FILE *test_fopen(const char *path, const char *mode) {
    if (!model_enabled || strcmp(path, "/proc/self/maps"))
        return fopen(path, mode);
    FILE *f = tmpfile();
    assert(f);
    for (unsigned i = 0; i < modeled_count; i++) {
        const struct modeled_vma *v = &modeled[i];
        int target = v->kind == RESERVATION && v->start == model_target;
        int foreign_target = v->kind == UNRELATED && v->start == model_target;
        char perms[] = "---p";
        if (v->protection & PROT_READ) perms[0] = 'r';
        if (v->protection & PROT_WRITE) perms[1] = 'w';
        if (v->protection & PROT_EXEC) perms[2] = 'x';
        if (target && model_target_shared) perms[3] = 's';
        fprintf(f, "%" PRIxPTR "-%" PRIxPTR " %s 00000000 %s %d%s\n", v->start, v->end,
                perms, (target && model_target_file) || foreign_target ? "01:03" : "00:00",
                (target && model_target_file) || foreign_target ? 123 : 0,
                (target && model_target_file) || foreign_target ? " /some/file" : "");
    }
    if (model_native) {
        /* Deliberately put the returned pointer one page into its VMA. */
        uintptr_t start = (uintptr_t)model_native - 4096;
        assert(start > model_limit);
        fprintf(f, "%" PRIxPTR "-%" PRIxPTR " rw-s %llx 01:02 4242 /dev/mali0\n",
                start, start + model_length + 8192, (unsigned long long)model_offset);
    }
    rewind(f);
    return f;
}
static ssize_t test_readlink(const char *path, char *buffer, size_t size) {
    int fd = -1;
    if (model_enabled && sscanf(path, "/proc/self/fd/%d", &fd) == 1 && modeled_fd(fd)) {
        const char name[] = "/dev/mali0";
        size_t n = sizeof(name) - 1;
        if (n > size) n = size;
        memcpy(buffer, name, n);
        return (ssize_t)n;
    }
    return readlink(path, buffer, size);
}
static int test_fstat(int fd, struct stat *st) {
    int result = fstat(fd, st);
    if (!result && modeled_fd(fd)) {
        st->st_dev = makedev(1, 2);
        st->st_ino = 4242;
    }
    return result;
}
static int test_close(int fd) {
    if (model_enabled)
        assert(fd != model_gpu_fd && fd != model_extra_fd);
    layer_closes++;
    return close(fd);
}
#define mmap test_mmap
#define munmap test_munmap
#define fopen test_fopen
#define readlink test_readlink
#define fstat test_fstat
#define dlopen test_dlopen
#define dlsym test_dlsym
#define close test_close
#include "placed_layer.c"
#undef mmap
#undef munmap
#undef fopen
#undef readlink
#undef fstat
#undef dlopen
#undef dlsym
#undef close
#include <assert.h>
#include <fcntl.h>
#include <stdatomic.h>
#include <stddef.h>
#include <sys/syscall.h>
#define memfd_create(name, flags) ((int)syscall(SYS_memfd_create, name, flags))

struct fake_dispatch {
    void *dispatch;
};
static int instance_key, device_key;
static struct fake_dispatch ih = {&instance_key}, ph = {&instance_key}, dh = {&device_key};
static VkInstance I = (VkInstance)&ih;
static VkPhysicalDevice P = (VkPhysicalDevice)&ph;
static VkDevice D = (VkDevice)&dh;
static _Atomic unsigned allocs, frees, native_maps, native_unmaps, driver_frees;
static int fail_native_map, short_fd, expect_filtered, explicit_failures, have_native_placed;
static int mali_mode, have_native_map2, core_map2_only, device_map2_enabled;
static unsigned forwarded_extension_count, native_map2_calls, native_unmap2_calls;
static int forwarded_fd, forwarded_placed;
static const VkMemoryMapInfoKHR *native_map2_info;
static const VkMemoryUnmapInfoKHR *native_unmap2_info;
static _Atomic VkExternalMemoryHandleTypeFlags last_export;
struct native_memory {
    int fd;
    size_t size;
    void *map;
};
struct native_resource {
    int image;
    VkImageCreateFlags flags;
    VkExternalMemoryHandleTypeFlags external;
};
static void *VKAPI_PTR ca(void *u, size_t n, size_t align, VkSystemAllocationScope s) {
    (void)u;
    (void)s;
    void *p = NULL;
    if (posix_memalign(&p, align, n))
        return NULL;
    allocs++;
    return p;
}
static void VKAPI_PTR cf(void *u, void *p) {
    (void)u;
    if (p) {
        frees++;
        free(p);
    }
}
static VkAllocationCallbacks callbacks = {.pfnAllocation = ca, .pfnFree = cf};
static void VKAPI_CALL dummy(void) {}
static VkResult VKAPI_CALL ne(VkPhysicalDevice p, const char *layer, uint32_t *n,
                              VkExtensionProperties *out) {
    assert(p == P);
    if (layer)
        return VK_ERROR_LAYER_NOT_PRESENT;
    uint32_t count = 1 + !!have_native_placed + !!have_native_map2;
    if (!out) {
        *n = count;
        return VK_SUCCESS;
    }
    uint32_t written = *n < count ? *n : count;
    if (written) {
        strcpy(out[0].extensionName, FD_EXT);
        out[0].specVersion = 1;
    }
    if (have_native_placed && written > 1) {
        strcpy(out[1].extensionName, PLACED);
        out[1].specVersion = 1;
    }
    if (have_native_map2 && written > 1u + !!have_native_placed) {
        strcpy(out[1 + !!have_native_placed].extensionName, MAP2);
        out[1 + !!have_native_placed].specVersion = 1;
    }
    *n = written;
    return written < count ? VK_INCOMPLETE : VK_SUCCESS;
}
static void VKAPI_CALL np(VkPhysicalDevice p, VkPhysicalDeviceMemoryProperties *out) {
    assert(p == P);
    if (mali_mode) {
        *out = (VkPhysicalDeviceMemoryProperties){.memoryTypeCount = 2, .memoryHeapCount = 1};
        out->memoryTypes[0].propertyFlags = VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT | HOST | COHERENT;
        out->memoryTypes[1].propertyFlags =
            VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT | HOST | VK_MEMORY_PROPERTY_HOST_CACHED_BIT;
        return;
    }
    *out = (VkPhysicalDeviceMemoryProperties){.memoryTypeCount = 3, .memoryHeapCount = 1};
    out->memoryTypes[0].propertyFlags = VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT;
    out->memoryTypes[1].propertyFlags = HOST | COHERENT;
    out->memoryTypes[2].propertyFlags = HOST | VK_MEMORY_PROPERTY_HOST_CACHED_BIT;
}
static void VKAPI_CALL nf(VkPhysicalDevice p, VkPhysicalDeviceFeatures2 *out) {
    assert(p == P);
    out->features.robustBufferAccess = VK_TRUE;
}
static void VKAPI_CALL nprop(VkPhysicalDevice p, VkPhysicalDeviceProperties2 *out) {
    assert(p == P);
    out->properties.apiVersion = core_map2_only ? VK_MAKE_API_VERSION(0, 1, 4, 0) : VK_API_VERSION_1_3;
    out->properties.vendorID = mali_mode == 1 ? 0x13b5 : 0;
}
static void VKAPI_CALL nprop1(VkPhysicalDevice p, VkPhysicalDeviceProperties *out) {
    VkPhysicalDeviceProperties2 props = {.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PROPERTIES_2};
    nprop(p, &props);
    *out = props.properties;
}
static void VKAPI_CALL nbprops(VkPhysicalDevice p, const VkPhysicalDeviceExternalBufferInfo *in,
                               VkExternalBufferProperties *out) {
    assert(p == P && in->handleType == OPAQUE);
    if (mali_mode) {
        out->externalMemoryProperties = (VkExternalMemoryProperties){0};
        return;
    }
    out->externalMemoryProperties = (VkExternalMemoryProperties){
        .externalMemoryFeatures =
            VK_EXTERNAL_MEMORY_FEATURE_EXPORTABLE_BIT | VK_EXTERNAL_MEMORY_FEATURE_IMPORTABLE_BIT,
        .compatibleHandleTypes = OPAQUE};
}
static VkResult VKAPI_CALL niprops(VkPhysicalDevice p, const VkPhysicalDeviceImageFormatInfo2 *in,
                                   VkImageFormatProperties2 *out) {
    assert(p == P);
    const VkPhysicalDeviceExternalImageFormatInfo *e =
        (void *)find_chain(in->pNext, VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_EXTERNAL_IMAGE_FORMAT_INFO);
    if ((in->format == VK_FORMAT_B8G8R8A8_UNORM && (in->usage & VK_IMAGE_USAGE_STORAGE_BIT)) ||
        (e && (in->flags & VK_IMAGE_CREATE_MUTABLE_FORMAT_BIT)))
        return VK_ERROR_FORMAT_NOT_SUPPORTED;
    out->imageFormatProperties = (VkImageFormatProperties){.maxExtent = {4096, 4096, 4096},
                                                           .maxMipLevels = 12,
                                                           .maxArrayLayers = 256,
                                                           .sampleCounts = VK_SAMPLE_COUNT_1_BIT,
                                                           .maxResourceSize = 1ull << 30};
    VkExternalImageFormatProperties *ep =
        (void *)find_chain(out->pNext, VK_STRUCTURE_TYPE_EXTERNAL_IMAGE_FORMAT_PROPERTIES);
    if (ep)
        ep->externalMemoryProperties = (VkExternalMemoryProperties){
            .externalMemoryFeatures = VK_EXTERNAL_MEMORY_FEATURE_EXPORTABLE_BIT |
                                      VK_EXTERNAL_MEMORY_FEATURE_IMPORTABLE_BIT,
            .compatibleHandleTypes = OPAQUE};
    return VK_SUCCESS;
}
static VkResult VKAPI_CALL nci(const VkInstanceCreateInfo *ci, const VkAllocationCallbacks *a,
                               VkInstance *out) {
    (void)ci;
    (void)a;
    *out = I;
    return VK_SUCCESS;
}
static void VKAPI_CALL ndi(VkInstance i, const VkAllocationCallbacks *a) {
    assert(i == I);
    (void)a;
}
static VkResult VKAPI_CALL ncd(VkPhysicalDevice p, const VkDeviceCreateInfo *ci,
                               const VkAllocationCallbacks *a, VkDevice *out) {
    assert(p == P);
    (void)a;
    forwarded_extension_count = ci->enabledExtensionCount;
    device_map2_enabled = forwarded_fd = forwarded_placed = 0;
    for (uint32_t j = 0; j < ci->enabledExtensionCount; j++) {
        device_map2_enabled |= !strcmp(ci->ppEnabledExtensionNames[j], MAP2);
        forwarded_fd |= !strcmp(ci->ppEnabledExtensionNames[j], FD_EXT);
        forwarded_placed |= !strcmp(ci->ppEnabledExtensionNames[j], PLACED);
    }
    if (expect_filtered) {
        assert(!find_chain(ci->pNext,
                           VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_MAP_MEMORY_PLACED_FEATURES_EXT));
        int fd = 0;
        for (uint32_t j = 0; j < ci->enabledExtensionCount; j++) {
            assert(strcmp(ci->ppEnabledExtensionNames[j], PLACED));
            assert(have_native_map2 || strcmp(ci->ppEnabledExtensionNames[j], MAP2));
            if (!strcmp(ci->ppEnabledExtensionNames[j], FD_EXT))
                fd = 1;
        }
        assert(fd);
    }
    *out = D;
    return VK_SUCCESS;
}
static void VKAPI_CALL ndd(VkDevice h, const VkAllocationCallbacks *a) {
    assert(h == D);
    (void)a;
}
static VkResult VKAPI_CALL nab(VkDevice h, const VkBufferCreateInfo *ci,
                               const VkAllocationCallbacks *a, VkBuffer *out) {
    assert(h == D);
    (void)a;
    struct native_resource *r = calloc(1, sizeof(*r));
    const VkExternalMemoryBufferCreateInfo *e =
        (void *)find_chain(ci->pNext, VK_STRUCTURE_TYPE_EXTERNAL_MEMORY_BUFFER_CREATE_INFO);
    if (e)
        r->external = e->handleTypes;
    *out = (VkBuffer)r;
    return VK_SUCCESS;
}
static VkResult VKAPI_CALL nai(VkDevice h, const VkImageCreateInfo *ci,
                               const VkAllocationCallbacks *a, VkImage *out) {
    assert(h == D);
    (void)a;
    struct native_resource *r = calloc(1, sizeof(*r));
    r->image = 1;
    r->flags = ci->flags;
    const VkExternalMemoryImageCreateInfo *e =
        (void *)find_chain(ci->pNext, VK_STRUCTURE_TYPE_EXTERNAL_MEMORY_IMAGE_CREATE_INFO);
    if (e)
        r->external = e->handleTypes;
    *out = (VkImage)r;
    return VK_SUCCESS;
}
static void VKAPI_CALL ndb(VkDevice h, VkBuffer r, const VkAllocationCallbacks *a) {
    assert(h == D);
    (void)a;
    free(r);
}
static void VKAPI_CALL ndimage(VkDevice h, VkImage r, const VkAllocationCallbacks *a) {
    assert(h == D);
    (void)a;
    free(r);
}
static void req(VkMemoryRequirements *out) {
    *out = (VkMemoryRequirements){.size = 4096, .alignment = 4096,
                                  .memoryTypeBits = mali_mode ? 3 : 7};
}
static void VKAPI_CALL nbr(VkDevice h, VkBuffer b, VkMemoryRequirements *out) {
    assert(h == D && b);
    req(out);
}
static void VKAPI_CALL nir(VkDevice h, VkImage b, VkMemoryRequirements *out) {
    assert(h == D && b);
    req(out);
}
static void VKAPI_CALL nbr2(VkDevice h, const VkBufferMemoryRequirementsInfo2 *in,
                            VkMemoryRequirements2 *out) {
    nbr(h, in->buffer, &out->memoryRequirements);
}
static void VKAPI_CALL nir2(VkDevice h, const VkImageMemoryRequirementsInfo2 *in,
                            VkMemoryRequirements2 *out) {
    nir(h, in->image, &out->memoryRequirements);
}
static void VKAPI_CALL ndbr(VkDevice h, const VkDeviceBufferMemoryRequirements *in,
                            VkMemoryRequirements2 *out) {
    assert(h == D && in->pCreateInfo);
    req(&out->memoryRequirements);
}
static void VKAPI_CALL ndir(VkDevice h, const VkDeviceImageMemoryRequirements *in,
                            VkMemoryRequirements2 *out) {
    assert(h == D && in->pCreateInfo);
    req(&out->memoryRequirements);
}
static VkResult VKAPI_CALL na(VkDevice h, const VkMemoryAllocateInfo *ci,
                              const VkAllocationCallbacks *a, VkDeviceMemory *out) {
    assert(h == D);
    (void)a;
    struct native_memory *m = calloc(1, sizeof(*m));
    m->size = (size_t)ci->allocationSize;
    m->fd = memfd_create("layer-test", MFD_CLOEXEC);
    assert(m->fd >= 0);
    assert(!ftruncate(m->fd, m->size));
    if (model_enabled) model_gpu_fd = m->fd;
    const VkExportMemoryAllocateInfo *e =
        (void *)find_chain(ci->pNext, VK_STRUCTURE_TYPE_EXPORT_MEMORY_ALLOCATE_INFO);
    last_export = e ? e->handleTypes : 0;
    *out = (VkDeviceMemory)m;
    return VK_SUCCESS;
}
static void VKAPI_CALL nfree(VkDevice h, VkDeviceMemory handle, const VkAllocationCallbacks *a) {
    assert(h == D);
    (void)a;
    if (!handle)
        return;
    struct native_memory *m = (void *)handle;
    assert(!m->map);
    close(m->fd);
    free(m);
    driver_frees++;
}
static VkResult VKAPI_CALL nm(VkDevice h, VkDeviceMemory handle, VkDeviceSize offset,
                              VkDeviceSize size, VkMemoryMapFlags flags, void **out) {
    assert(h == D && !flags);
    assert(!mapping_lock_depth);
    struct native_memory *m = (void *)handle;
    assert(offset < m->size);
    assert(size == VK_WHOLE_SIZE || (size && size <= m->size - offset));
    assert(!m->map);
    if (fail_native_map)
        return VK_ERROR_MEMORY_MAP_FAILED;
    m->map = mmap(NULL, m->size, PROT_READ | PROT_WRITE, MAP_SHARED, m->fd, 0);
    assert(m->map != MAP_FAILED);
    *out = (char *)m->map + offset;
    if (model_enabled) model_native = m->map;
    native_maps++;
    return VK_SUCCESS;
}
static void VKAPI_CALL nu(VkDevice h, VkDeviceMemory handle) {
    assert(h == D);
    assert(!mapping_lock_depth);
    struct native_memory *m = (void *)handle;
    assert(m->map);
    assert(!munmap(m->map, m->size));
    m->map = NULL;
    if (model_enabled) model_native = NULL;
    native_unmaps++;
}
static VkResult VKAPI_CALL nm2(VkDevice h, const VkMemoryMapInfoKHR *ci, void **out) {
    native_map2_calls++;
    native_map2_info = ci;
    return nm(h, ci->memory, ci->offset, ci->size, ci->flags, out);
}
static VkResult VKAPI_CALL nu2(VkDevice h, const VkMemoryUnmapInfoKHR *ci) {
    assert(!ci->flags);
    native_unmap2_calls++;
    native_unmap2_info = ci;
    nu(h, ci->memory);
    return VK_SUCCESS;
}
static VkResult VKAPI_CALL nfd(VkDevice h, const VkMemoryGetFdInfoKHR *in, int *out) {
    assert(h == D && in->handleType == OPAQUE);
    struct native_memory *m = (void *)in->memory;
    *out = dup(m->fd);
    assert(*out >= 0);
    if (short_fd) {
        close(*out);
        *out = memfd_create("short", MFD_CLOEXEC);
        assert(!ftruncate(*out, 1));
    }
    return VK_SUCCESS;
}
static PFN_vkVoidFunction VKAPI_CALL ngdpa(VkDevice h, const char *n) {
    assert(h == D);
#define N(name, func)                                                                              \
    if (!strcmp(n, "vk" name))                                                                     \
        return (PFN_vkVoidFunction)func;
    N("DestroyDevice", ndd)
    N("CreateBuffer", nab) N("DestroyBuffer", ndb) N("CreateImage", nai) N("DestroyImage", ndimage)
        N("GetBufferMemoryRequirements", nbr) N("GetImageMemoryRequirements", nir)
            N("GetBufferMemoryRequirements2", nbr2) N("GetBufferMemoryRequirements2KHR", nbr2)
                N("GetImageMemoryRequirements2", nir2) N("GetImageMemoryRequirements2KHR", nir2)
                    N("GetDeviceBufferMemoryRequirements", ndbr)
                        N("GetDeviceImageMemoryRequirements", ndir) N("AllocateMemory", na)
                            N("FreeMemory", nfree) N("MapMemory", nm) N("UnmapMemory", nu)
                                N("GetMemoryFdKHR", nfd)
#undef N
    if (map2_name(n)) {
        if (core_map2_only) {
            if (strstr(n, "KHR")) return NULL;
        } else if (!have_native_map2 || !device_map2_enabled) return NULL;
        return !strcmp(n, "vkMapMemory2KHR") || !strcmp(n, "vkMapMemory2")
                   ? (PFN_vkVoidFunction)nm2 : (PFN_vkVoidFunction)nu2;
    }
    return (PFN_vkVoidFunction)dummy;
}
static PFN_vkVoidFunction VKAPI_CALL ngipa(VkInstance h, const char *n) {
    (void)h;
#define N(name, func)                                                                              \
    if (!strcmp(n, "vk" name))                                                                     \
        return (PFN_vkVoidFunction)func;
    N("CreateInstance", nci)
    N("DestroyInstance", ndi) N("CreateDevice", ncd) N("EnumerateDeviceExtensionProperties", ne)
        N("GetPhysicalDeviceMemoryProperties", np) N("GetPhysicalDeviceFeatures2", nf)
            N("GetPhysicalDeviceProperties", nprop1)
            N("GetPhysicalDeviceFeatures2KHR", nf) N("GetPhysicalDeviceProperties2", nprop)
                N("GetPhysicalDeviceProperties2KHR", nprop)
                    N("GetPhysicalDeviceExternalBufferProperties", nbprops)
                        N("GetPhysicalDeviceImageFormatProperties2", niprops)
#undef N
                            return ngdpa(D, n);
}
static void setup(int enabled) {
    if (enabled)
        setenv("DROIDDECK_EXPERIMENTAL_PLACED_MEMORY", "1", 1);
    else
        unsetenv("DROIDDECK_EXPERIMENTAL_PLACED_MEMORY");
    VkLayerInstanceLink il = {.pfnNextGetInstanceProcAddr = ngipa};
    VkLayerInstanceCreateInfo lc = {.sType = VK_STRUCTURE_TYPE_LOADER_INSTANCE_CREATE_INFO,
                                    .function = VK_LAYER_LINK_INFO,
                                    .u.pLayerInfo = &il};
    VkInstanceCreateInfo ci = {.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO, .pNext = &lc};
    VkInstance out;
    assert(l_CreateInstance(&ci, &callbacks, &out) == VK_SUCCESS && out == I);
}
static void create_device_features(int enabled, int placed_features) {
    VkLayerDeviceLink link = {.pfnNextGetInstanceProcAddr = ngipa,
                              .pfnNextGetDeviceProcAddr = ngdpa};
    VkLayerDeviceCreateInfo lc = {.sType = VK_STRUCTURE_TYPE_LOADER_DEVICE_CREATE_INFO,
                                  .function = VK_LAYER_LINK_INFO,
                                  .u.pLayerInfo = &link};
    VkPhysicalDeviceMapMemoryPlacedFeaturesEXT pf = {
        .sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_MAP_MEMORY_PLACED_FEATURES_EXT,
        .pNext = &lc,
        .memoryMapPlaced = placed_features,
        .memoryUnmapReserve = placed_features};
    VkPhysicalDeviceVulkan12Features prefix = {
        .sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_2_FEATURES,
        .pNext = &pf,
        .timelineSemaphore = VK_TRUE};
    VkPhysicalDeviceVulkan12Features saved = prefix;
    const char *exts[] = {PLACED, MAP2};
    VkDeviceCreateInfo ci = {.sType = VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO,
                             .pNext = enabled ? &prefix : (void *)&lc,
                             .enabledExtensionCount = enabled ? 2 : 0,
                             .ppEnabledExtensionNames = exts};
    expect_filtered = enabled;
    VkDevice out;
    assert(l_CreateDevice(P, &ci, &callbacks, &out) == VK_SUCCESS && out == D);
    assert(!memcmp(&saved, &prefix, sizeof(prefix)));
    assert(pf.pNext == &lc);
}
static void create_device(int enabled) {
    create_device_features(enabled, 1);
}
static void teardown(void) {
    l_DestroyDevice(D, &callbacks);
    l_DestroyInstance(I, &callbacks);
    assert(!devices && !instances);
    assert(allocs == frees);
}
static void test_dispatch(void) {
    setup(0);
    create_device(0);
    assert(!l_GetDeviceProcAddr(D, "vkMapMemory2KHR"));
    assert(l_GetDeviceProcAddr(D, "vkCreateBuffer") == (PFN_vkVoidFunction)nab);
    uint32_t n = 0;
    assert(l_EnumerateDeviceExtensionProperties(P, NULL, &n, NULL) == VK_SUCCESS && n == 1);
    teardown();
    setup(1);
    create_device(1);
    assert(l_GetDeviceProcAddr(D, "vkMapMemory2KHR") == l_GetDeviceProcAddr(D, "vkMapMemory2"));
    assert(l_GetInstanceProcAddr(I, "vkMapMemory2KHR"));
    assert(l_GetPhysicalDeviceProcAddr(I, "vkGetPhysicalDeviceFeatures2KHR") ==
           (PFN_vkVoidFunction)l_GetPhysicalDeviceFeatures2KHR);
    n = 0;
    assert(l_EnumerateDeviceExtensionProperties(P, NULL, &n, NULL) == VK_SUCCESS && n == 3);
    VkExtensionProperties ep[3];
    n = 1;
    assert(l_EnumerateDeviceExtensionProperties(P, NULL, &n, ep) == VK_INCOMPLETE && n == 1);
    n = 3;
    assert(l_EnumerateDeviceExtensionProperties(P, LAYER_NAME, &n, ep) == VK_SUCCESS && n == 2);
    n = 0;
    assert(l_EnumerateDeviceExtensionProperties(P, "other", &n, NULL) ==
           VK_ERROR_LAYER_NOT_PRESENT);
    VkPhysicalDeviceMapMemoryPlacedFeaturesEXT pf = {
        .sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_MAP_MEMORY_PLACED_FEATURES_EXT};
    VkPhysicalDeviceFeatures2 f = {.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2,
                                   .pNext = &pf};
    l_GetPhysicalDeviceFeatures2KHR(P, &f);
    assert(pf.memoryMapPlaced && !pf.memoryMapRangePlaced && pf.memoryUnmapReserve &&
           f.features.robustBufferAccess);
    VkPhysicalDeviceMapMemoryPlacedPropertiesEXT pp = {
        .sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_MAP_MEMORY_PLACED_PROPERTIES_EXT};
    VkPhysicalDeviceProperties2 props = {.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PROPERTIES_2,
                                         .pNext = &pp};
    l_GetPhysicalDeviceProperties2(P, &props);
    assert(pp.minPlacedMemoryMapAlignment == page_size());
    teardown();
    puts("PASS dispatch: disabled pass-through, core/KHR aliases, feature/property exposure, "
         "extension enumeration, immutable chains");
}
static void create_map2_device(int enabled) {
    VkLayerDeviceLink link = {.pfnNextGetInstanceProcAddr = ngipa,
                              .pfnNextGetDeviceProcAddr = ngdpa};
    VkLayerDeviceCreateInfo lc = {.sType = VK_STRUCTURE_TYPE_LOADER_DEVICE_CREATE_INFO,
                                  .function = VK_LAYER_LINK_INFO,
                                  .u.pLayerInfo = &link};
    const char *extensions[] = {MAP2};
    VkDeviceCreateInfo ci = {.sType = VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO,
                             .pNext = &lc,
                             .enabledExtensionCount = enabled ? 1 : 0,
                             .ppEnabledExtensionNames = extensions};
    expect_filtered = 0;
    VkDevice out;
    assert(l_CreateDevice(P, &ci, &callbacks, &out) == VK_SUCCESS && out == D);
}
static void test_map2_only(void) {
    for (int route = 0; route < 3; route++) {
        int native = route != 0;
        have_native_map2 = route == 1;
        core_map2_only = route == 2;
        setup(1);
        uint32_t count = 4;
        VkExtensionProperties extensions[4];
        assert(l_EnumerateDeviceExtensionProperties(P, NULL, &count, extensions) == VK_SUCCESS);
        unsigned map2_count = 0;
        for (uint32_t j = 0; j < count; j++)
            map2_count += !strcmp(extensions[j].extensionName, MAP2);
        assert(map2_count == 1);
        create_map2_device(1);
        PFN_vkMapMemory2KHR map = (void *)l_GetDeviceProcAddr(D, "vkMapMemory2KHR");
        PFN_vkUnmapMemory2KHR unmap = (void *)l_GetDeviceProcAddr(D, "vkUnmapMemory2KHR");
        assert(map && unmap);
        assert((PFN_vkVoidFunction)map == l_GetDeviceProcAddr(D, "vkMapMemory2"));
        assert((PFN_vkVoidFunction)unmap == l_GetDeviceProcAddr(D, "vkUnmapMemory2"));
        assert(forwarded_extension_count == (unsigned)have_native_map2);
        assert(device_map2_enabled == have_native_map2 && !forwarded_fd && !forwarded_placed);
        assert(l_GetDeviceProcAddr(D, "vkCreateBuffer") == (PFN_vkVoidFunction)nab);
        assert(l_GetDeviceProcAddr(D, "vkAllocateMemory") == (PFN_vkVoidFunction)na);
        assert(!get_device(D)->active && !get_device(D)->placed && !get_device(D)->reserve);
        VkMemoryAllocateInfo allocation = {.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO,
                                            .allocationSize = 4096,
                                            .memoryTypeIndex = 1};
        VkDeviceMemory memory;
        assert(l_AllocateMemory(D, &allocation, &callbacks, &memory) == VK_SUCCESS);
        assert(!last_export && !get_device(D)->memories);
        VkMemoryMapInfoKHR mi = {.sType = VK_STRUCTURE_TYPE_MEMORY_MAP_INFO_KHR,
                                 .memory = memory, .offset = 128, .size = 256};
        VkMemoryUnmapInfoKHR ui = {.sType = VK_STRUCTURE_TYPE_MEMORY_UNMAP_INFO_KHR,
                                   .memory = memory};
        unsigned maps_before = native_map2_calls, unmaps_before = native_unmap2_calls;
        void *out = NULL;
        assert(map(D, &mi, &out) == VK_SUCCESS && out);
        assert(out == (char *)((struct native_memory *)memory)->map + mi.offset);
        memset(out, 0x29, (size_t)mi.size);
        assert(unmap(D, &ui) == VK_SUCCESS);
        if (native) {
            assert(native_map2_calls == maps_before + 1 && native_map2_info == &mi);
            assert(native_unmap2_calls == unmaps_before + 1 && native_unmap2_info == &ui);
        } else {
            assert(native_map2_calls == maps_before && native_unmap2_calls == unmaps_before);
            mi.flags = VK_MEMORY_MAP_PLACED_BIT_EXT;
            assert(map(D, &mi, &out) == VK_ERROR_MEMORY_MAP_FAILED);
            mi.flags = 0;
            VkMemoryMapPlacedInfoEXT placed = {.sType = VK_STRUCTURE_TYPE_MEMORY_MAP_PLACED_INFO_EXT};
            mi.pNext = &placed;
            assert(map(D, &mi, &out) == VK_ERROR_MEMORY_MAP_FAILED);
            mi.pNext = NULL;
            assert(map(D, &mi, &out) == VK_SUCCESS);
            ui.flags = VK_MEMORY_UNMAP_RESERVE_BIT_EXT;
            assert(unmap(D, &ui) == VK_ERROR_MEMORY_MAP_FAILED);
            assert((char *)((struct native_memory *)memory)->map + mi.offset == out);
            ui.flags = 0;
            assert(unmap(D, &ui) == VK_SUCCESS);
        }
        /* Instance dispatch also enters our wrapper on native-only devices. */
        map = (void *)l_GetInstanceProcAddr(I, "vkMapMemory2");
        unmap = (void *)l_GetInstanceProcAddr(I, "vkUnmapMemory2KHR");
        assert(map && unmap && map(D, &mi, &out) == VK_SUCCESS);
        assert(unmap(D, &ui) == VK_SUCCESS);
        l_FreeMemory(D, memory, &callbacks);
        teardown();

        setup(1);
        create_map2_device(0);
        assert(!l_GetDeviceProcAddr(D, "vkMapMemory2KHR"));
        assert(l_GetDeviceProcAddr(D, "vkUnmapMemory2") ==
               (core_map2_only ? (PFN_vkVoidFunction)nu2 : NULL));
        assert(!forwarded_extension_count && !get_device(D)->active);
        teardown();
    }
    have_native_map2 = core_map2_only = 0;
    assert(native_maps == native_unmaps);
    puts("PASS map2 only: emulated/native core and KHR dispatch, extension filtering, "
         "native resources, unsupported placed flags rejected, disabled extension stays disabled");
}
static void test_disabled_placed_features(void) {
    for (int native = 0; native < 2; native++) {
        have_native_map2 = native;
        setup(1);
        create_device_features(1, 0);
        assert(device_map2_enabled == native && forwarded_fd && !forwarded_placed);
        assert(forwarded_extension_count == 1u + native);
        VkMemoryAllocateInfo allocation = {.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO,
                                            .allocationSize = 4096,
                                            .memoryTypeIndex = 1};
        VkDeviceMemory memory;
        assert(l_AllocateMemory(D, &allocation, &callbacks, &memory) == VK_SUCCESS);
        VkMemoryMapInfoKHR mi = {.sType = VK_STRUCTURE_TYPE_MEMORY_MAP_INFO_KHR,
                                 .memory = memory, .size = VK_WHOLE_SIZE};
        VkMemoryUnmapInfoKHR ui = {.sType = VK_STRUCTURE_TYPE_MEMORY_UNMAP_INFO_KHR,
                                   .memory = memory};
        void *out = NULL;
        mi.flags = VK_MEMORY_MAP_PLACED_BIT_EXT;
        assert(l_MapMemory2KHR(D, &mi, &out) == VK_ERROR_MEMORY_MAP_FAILED);
        mi.flags = 0;
        assert(l_MapMemory2KHR(D, &mi, &out) == VK_SUCCESS && out);
        ui.flags = VK_MEMORY_UNMAP_RESERVE_BIT_EXT;
        assert(l_UnmapMemory2KHR(D, &ui) == VK_ERROR_MEMORY_MAP_FAILED);
        ui.flags = 0;
        assert(l_UnmapMemory2KHR(D, &ui) == VK_SUCCESS);
        l_FreeMemory(D, memory, &callbacks);
        teardown();
    }
    have_native_map2 = 0;
    puts("PASS placed features disabled: native map2 extension preserved, ordinary mapping works, "
         "placement and reservation stay disabled");
}
static void test_resources(void) {
    setup(1);
    create_device(1);
    VkImageCreateInfo ci = {.sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO,
                            .imageType = VK_IMAGE_TYPE_2D,
                            .format = VK_FORMAT_R8G8B8A8_UNORM,
                            .extent = {64, 64, 1},
                            .mipLevels = 1,
                            .arrayLayers = 1,
                            .samples = VK_SAMPLE_COUNT_1_BIT,
                            .tiling = VK_IMAGE_TILING_OPTIMAL,
                            .usage = VK_IMAGE_USAGE_SAMPLED_BIT};
    for (int mut = 0; mut < 2; mut++) {
        ci.flags = mut ? VK_IMAGE_CREATE_MUTABLE_FORMAT_BIT : 0;
        VkImage img;
        assert(l_CreateImage(D, &ci, &callbacks, &img) == VK_SUCCESS);
        assert(((struct native_resource *)img)->external == (mut ? 0 : OPAQUE));
        VkMemoryRequirements r;
        l_GetImageMemoryRequirements(D, img, &r);
        assert(r.memoryTypeBits == (mut ? 1 : 7));
        VkImageMemoryRequirementsInfo2 ri = {
            .sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_REQUIREMENTS_INFO_2, .image = img};
        VkMemoryRequirements2 r2 = {.sType = VK_STRUCTURE_TYPE_MEMORY_REQUIREMENTS_2};
        l_GetImageMemoryRequirements2(D, &ri, &r2);
        assert(r2.memoryRequirements.memoryTypeBits == r.memoryTypeBits);
        VkDeviceImageMemoryRequirements di = {
            .sType = VK_STRUCTURE_TYPE_DEVICE_IMAGE_MEMORY_REQUIREMENTS, .pCreateInfo = &ci};
        l_GetDeviceImageMemoryRequirements(D, &di, &r2);
        assert(r2.memoryRequirements.memoryTypeBits == r.memoryTypeBits);
        l_DestroyImage(D, img, &callbacks);
    }
    VkExternalMemoryImageCreateInfo ext = {
        .sType = VK_STRUCTURE_TYPE_EXTERNAL_MEMORY_IMAGE_CREATE_INFO,
        .handleTypes = VK_EXTERNAL_MEMORY_HANDLE_TYPE_DMA_BUF_BIT_EXT};
    ci.pNext = &ext;
    VkImage img;
    assert(l_CreateImage(D, &ci, &callbacks, &img) == VK_ERROR_INVALID_EXTERNAL_HANDLE);
    assert(ext.handleTypes == VK_EXTERNAL_MEMORY_HANDLE_TYPE_DMA_BUF_BIT_EXT);
    explicit_failures++;
    VkBufferCreateInfo bc = {.sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO,
                             .size = 4096,
                             .usage = VK_BUFFER_USAGE_TRANSFER_SRC_BIT};
    VkBuffer b;
    assert(l_CreateBuffer(D, &bc, &callbacks, &b) == VK_SUCCESS &&
           ((struct native_resource *)b)->external == OPAQUE);
    l_DestroyBuffer(D, b, &callbacks);
    teardown();
    puts("PASS resources: native mutable-image fallback, all requirements paths, OPAQUE create "
         "parity, explicit unsupported contract rejection");
}
static VkDeviceMemory allocate(void) {
    VkMemoryAllocateInfo ci = {.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO,
                               .allocationSize = 4096,
                               .memoryTypeIndex = 1};
    VkDeviceMemory m;
    assert(l_AllocateMemory(D, &ci, &callbacks, &m) == VK_SUCCESS);
    assert(last_export == OPAQUE);
    return m;
}
static void test_mapping(void) {
    setup(1);
    create_device(1);
    VkDeviceMemory m = allocate();
    void *reservation = mmap(NULL, 4096, PROT_NONE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    assert(reservation != MAP_FAILED);
    VkMemoryMapPlacedInfoEXT p = {.sType = VK_STRUCTURE_TYPE_MEMORY_MAP_PLACED_INFO_EXT,
                                  .pPlacedAddress = reservation};
    VkMemoryMapInfoKHR info = {.sType = VK_STRUCTURE_TYPE_MEMORY_MAP_INFO_KHR,
                               .pNext = &p,
                               .flags = VK_MEMORY_MAP_PLACED_BIT_EXT,
                               .memory = m,
                               .size = VK_WHOLE_SIZE};
    void *out = NULL;
    info.offset = 4;
    assert(l_MapMemory2KHR(D, &info, &out) == VK_ERROR_MEMORY_MAP_FAILED);
    info.offset = 0;
    short_fd = 1;
    int closes = layer_closes;
    assert(l_MapMemory2KHR(D, &info, &out) == VK_ERROR_MEMORY_MAP_FAILED &&
           layer_closes == closes + 1);
    short_fd = 0;
    fail_native_map = 1;
    assert(l_MapMemory2KHR(D, &info, &out) == VK_ERROR_MEMORY_MAP_FAILED);
    fail_native_map = 0;
    fail_fixed_map = 1;
    unsigned before = native_unmaps;
    assert(l_MapMemory2KHR(D, &info, &out) == VK_ERROR_MEMORY_MAP_FAILED &&
           native_unmaps == before + 1);
    assert(!get_memory(get_device(D), m)->native);
    fail_fixed_map = 0;
    assert(l_MapMemory2KHR(D, &info, &out) == VK_SUCCESS && out == reservation);
    memset(out, 0x45, 4096);
    assert(((unsigned char *)((struct native_memory *)m)->map)[300] == 0x45);
    assert(l_MapMemory2KHR(D, &info, &out) == VK_ERROR_MEMORY_MAP_FAILED);
    VkMemoryUnmapInfoKHR ui = {.sType = VK_STRUCTURE_TYPE_MEMORY_UNMAP_INFO_KHR,
                               .memory = m,
                               .flags = VK_MEMORY_UNMAP_RESERVE_BIT_EXT};
    fail_reserve_map = 1;
    assert(l_UnmapMemory2KHR(D, &ui) == VK_ERROR_MEMORY_MAP_FAILED &&
           ((unsigned char *)out)[300] == 0x45);
    fail_reserve_map = 0;
    assert(l_UnmapMemory2KHR(D, &ui) == VK_SUCCESS);
    assert(!get_memory(get_device(D), m)->alias && !get_memory(get_device(D), m)->native);
    assert(mmap(reservation, 4096, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS | MAP_FIXED,
                -1, 0) == reservation);
    memset(reservation, 0x67, 4096);
    closes = layer_closes;
    l_FreeMemory(D, m, &callbacks);
    assert(layer_closes == closes + 1);
    assert(((unsigned char *)reservation)[0] == 0x67);
    munmap(reservation, 4096);
    m = allocate();
    assert(l_MapMemory(D, m, 0, VK_WHOLE_SIZE, 0, &out) == VK_SUCCESS);
    l_UnmapMemory(D, m);
    l_FreeMemory(D, m, &callbacks);
    teardown();
    assert(native_maps == native_unmaps);
    puts("PASS mapping: alias coherence, duplicate/bounds rejection, map failure rollback, reserve "
         "failure preserves mapping, reserve/free avoids reused VA, fd/custom allocator cleanup");
}
static void *allocation_worker(void *unused) {
    (void)unused;
    for (int n = 0; n < 32; n++) {
        VkMemoryAllocateInfo ci = {.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO,
                                   .allocationSize = 4096,
                                   .memoryTypeIndex = 1};
        VkDeviceMemory m;
        assert(l_AllocateMemory(D, &ci, &callbacks, &m) == VK_SUCCESS);
        l_FreeMemory(D, m, &callbacks);
    }
    return NULL;
}
static void test_concurrency(void) {
    setup(1);
    create_device(1);
    pthread_t threads[4];
    for (unsigned n = 0; n < 4; n++)
        assert(!pthread_create(&threads[n], NULL, allocation_worker, NULL));
    for (unsigned n = 0; n < 4; n++)
        assert(!pthread_join(threads[n], NULL));
    assert(!get_device(D)->memories);
    VkBaseInStructure imported = {
        .sType = VK_STRUCTURE_TYPE_IMPORT_ANDROID_HARDWARE_BUFFER_INFO_ANDROID};
    VkMemoryAllocateInfo ci = {.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO,
                               .pNext = &imported,
                               .allocationSize = 4096,
                               .memoryTypeIndex = 1};
    VkDeviceMemory m;
    assert(l_AllocateMemory(D, &ci, &callbacks, &m) == VK_ERROR_INVALID_EXTERNAL_HANDLE);
    teardown();
    puts("PASS concurrency: 128 concurrent allocations/frees, reused device keys, unknown/import "
         "allocation chains fail closed");
}
static void reset_model(void) {
    modeled_count = guard_calls = chunk_calls = 0;
    model_released = fail_guard = fail_chunk = wrong_chunk = race_restore = 0;
    model_length = 2 * 1024 * 1024 + 65536;
    model_target_protection = PROT_READ | PROT_WRITE;
    model_target_shared = model_target_file = 0;
    model_native = NULL;
    model_gpu_fd = model_extra_fd = -1;
    model_add(model_target, model_length, RESERVATION);
    model_add(0x30000000, 4096, UNRELATED);
}
static void assert_model_clean(int raced, int protection) {
    assert(modeled_count == 2);
    assert(modeled[0].start == model_target);
    assert(modeled[0].end == model_target + (raced ? 4096 : model_length));
    assert(modeled[0].kind == (raced ? UNRELATED : RESERVATION));
    assert(modeled[0].protection == protection);
    assert(modeled[1].start == 0x30000000 && modeled[1].kind == UNRELATED);
    assert(!model_native);
}
static VkDeviceMemory allocate_mali(void) {
    VkMemoryAllocateInfo ci = {.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO,
                               .allocationSize = model_length, .memoryTypeIndex = 0};
    VkDeviceMemory m;
    assert(l_AllocateMemory(D, &ci, &callbacks, &m) == VK_SUCCESS);
    assert(!last_export && get_memory(get_device(D), m)->exportable);
    return m;
}
static VkResult map_mali(VkDeviceMemory memory, void **out) {
    VkMemoryMapPlacedInfoEXT placed = {.sType = VK_STRUCTURE_TYPE_MEMORY_MAP_PLACED_INFO_EXT,
                                      .pPlacedAddress = (void *)model_target};
    VkMemoryMapInfoKHR map = {.sType = VK_STRUCTURE_TYPE_MEMORY_MAP_INFO_KHR, .pNext = &placed,
                              .flags = VK_MEMORY_MAP_PLACED_BIT_EXT, .memory = memory,
                              .size = VK_WHOLE_SIZE};
    mapping_attempt = 1;
    VkResult result = l_MapMemory2KHR(D, &map, out);
    mapping_attempt = 0;
    assert(!mapping_lock_depth && mapping_begins == mapping_ends);
    return result;
}
static void test_mali_resources(void) {
    mali_mode = 1;
    setup(0);
    create_device(0);
    assert(l_GetDeviceProcAddr(D, "vkCreateImage") == (PFN_vkVoidFunction)nai);
    teardown();
    have_native_placed = 1;
    setup(1);
    create_device(0);
    assert(l_GetDeviceProcAddr(D, "vkCreateImage") == (PFN_vkVoidFunction)nai);
    teardown();
    have_native_placed = 0;
    setup(1);
    create_device(1);
    assert(get_device(D)->backend == BACKEND_NATIVE_ALIAS);
    assert(get_device(D)->props.memoryTypeCount == 2);
    assert(get_device(D)->props.memoryTypes[0].propertyFlags == 7);
    assert(get_device(D)->props.memoryTypes[1].propertyFlags == 11);
    VkImageCreateInfo ci = {.sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO,
                            .flags = VK_IMAGE_CREATE_MUTABLE_FORMAT_BIT,
                            .imageType = VK_IMAGE_TYPE_2D, .format = VK_FORMAT_B8G8R8A8_UNORM,
                            .extent = {1602, 720, 1}, .mipLevels = 1, .arrayLayers = 1,
                            .samples = VK_SAMPLE_COUNT_4_BIT, .tiling = VK_IMAGE_TILING_OPTIMAL,
                            .usage = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT |
                                     VK_IMAGE_USAGE_SAMPLED_BIT};
    VkImage img;
    assert(l_CreateImage(D, &ci, &callbacks, &img) == VK_SUCCESS);
    assert(!((struct native_resource *)img)->external);
    VkMemoryRequirements requirements;
    l_GetImageMemoryRequirements(D, img, &requirements);
    assert(requirements.memoryTypeBits == 3);
    VkImageMemoryRequirementsInfo2 ii = {
        .sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_REQUIREMENTS_INFO_2, .image = img};
    VkMemoryRequirements2 r2 = {.sType = VK_STRUCTURE_TYPE_MEMORY_REQUIREMENTS_2};
    l_GetImageMemoryRequirements2(D, &ii, &r2);
    assert(r2.memoryRequirements.memoryTypeBits == 3);
    VkDeviceImageMemoryRequirements di = {
        .sType = VK_STRUCTURE_TYPE_DEVICE_IMAGE_MEMORY_REQUIREMENTS, .pCreateInfo = &ci};
    l_GetDeviceImageMemoryRequirements(D, &di, &r2);
    assert(r2.memoryRequirements.memoryTypeBits == 3);
    l_DestroyImage(D, img, &callbacks);
    VkBufferCreateInfo bc = {.sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO, .size = 4096,
                             .usage = VK_BUFFER_USAGE_TRANSFER_SRC_BIT};
    VkBuffer buffer;
    assert(l_CreateBuffer(D, &bc, &callbacks, &buffer) == VK_SUCCESS);
    assert(!((struct native_resource *)buffer)->external);
    l_GetBufferMemoryRequirements(D, buffer, &requirements);
    assert(requirements.memoryTypeBits == 3);
    l_DestroyBuffer(D, buffer, &callbacks);
    VkBaseInStructure imported = {
        .sType = VK_STRUCTURE_TYPE_IMPORT_ANDROID_HARDWARE_BUFFER_INFO_ANDROID};
    VkMemoryAllocateInfo alloc = {.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO,
                                  .pNext = &imported, .allocationSize = 4096,
                                  .memoryTypeIndex = 0};
    VkDeviceMemory memory;
    assert(l_AllocateMemory(D, &alloc, &callbacks, &memory) == VK_SUCCESS);
    assert(!last_export && !get_memory(get_device(D), memory)->exportable);
    void *out = NULL;
    unsigned before = native_maps;
    assert(map_mali(memory, &out) == VK_ERROR_MEMORY_MAP_FAILED && native_maps == before);
    l_FreeMemory(D, memory, &callbacks);
    teardown();
    mali_mode = 0;
    puts("PASS mali resources: native unified types and mutable MSAA image preserved, no external "
         "memory injection, imported allocations excluded, opt-out/native placed unchanged");
}
static void test_mali_placement(void) {
    mali_mode = model_enabled = 1;
    setup(1);
    create_device(1);
    reset_model();
    VkDeviceMemory memory = allocate_mali();
    void *out = NULL;
    assert(map_mali(memory, &out) == VK_SUCCESS && out == (void *)model_target);
    assert(chunk_calls == 3 && guard_calls == 2);
    assert(chunk_lengths[0] == 1024 * 1024 && chunk_lengths[1] == 1024 * 1024 &&
           chunk_lengths[2] == 65536);
    assert(chunk_offsets[0] == model_offset + 4096 + 1024 * 1024 + 65536);
    assert(chunk_offsets[1] == model_offset + 4096 + 65536);
    assert(chunk_offsets[2] == model_offset + 4096);
    assert(model_native == ((struct native_memory *)memory)->map);
    assert(modeled_count == 4); /* Three alias chunks and unrelated VMA; guards/probes gone. */
    assert(map_mali(memory, &out) == VK_ERROR_MEMORY_MAP_FAILED);
    VkMemoryUnmapInfoKHR unmap = {.sType = VK_STRUCTURE_TYPE_MEMORY_UNMAP_INFO_KHR,
                                 .memory = memory, .flags = VK_MEMORY_UNMAP_RESERVE_BIT_EXT};
    fail_reserve_map = 1;
    assert(l_UnmapMemory2KHR(D, &unmap) == VK_ERROR_MEMORY_MAP_FAILED && model_native);
    fail_reserve_map = 0;
    assert(l_UnmapMemory2KHR(D, &unmap) == VK_SUCCESS);
    assert_model_clean(0, PROT_NONE);
    /* A completed placement must not leave a duplicate descriptor that looks
     * like a second Mali context on the next placement. */
    assert(get_memory(get_device(D), memory)->fd == -1);
    model_released = 0;
    guard_calls = chunk_calls = 0;
    assert(map_mali(memory, &out) == VK_SUCCESS && out == (void *)model_target);
    assert(chunk_calls == 3 && get_memory(get_device(D), memory)->fd == -1);
    assert(l_UnmapMemory2KHR(D, &unmap) == VK_SUCCESS);
    assert_model_clean(0, PROT_NONE);
    l_FreeMemory(D, memory, &callbacks);
    assert_model_clean(0, PROT_NONE);

    /* All failures must unwind owned aliases/guards without touching the
     * original driver VMA or a concurrent thread's replacement mapping. */
    for (int failure = 0; failure < 10; failure++) {
        reset_model();
        if (failure == 6) {
            model_target_protection |= PROT_EXEC;
            modeled[0].protection = model_target_protection;
        }
        memory = allocate_mali();
        if (failure == 0) fail_guard = 2;
        if (failure == 1) fail_chunk = 2;
        if (failure == 2) wrong_chunk = 2;
        if (failure == 3) model_extra_fd = dup(model_gpu_fd);
        if (failure == 4) fail_native_map = 1;
        if (failure == 5) { fail_chunk = 2; race_restore = 1; }
        if (failure == 6) fail_chunk = 2;
        if (failure == 7) model_target_shared = 1;
        if (failure == 8) model_target_file = 1;
        if (failure == 9) fail_mapping_begin = 1;
        unsigned before = native_unmaps;
        assert(map_mali(memory, &out) == VK_ERROR_MEMORY_MAP_FAILED);
        assert(native_unmaps == before + (failure != 4 && (failure < 7 || failure == 9)));
        assert(!get_memory(get_device(D), memory)->native);
        assert(!get_memory(get_device(D), memory)->alias);
        assert_model_clean(failure == 5, model_target_protection);
        if (model_extra_fd >= 0) close(model_extra_fd);
        fail_native_map = 0;
        l_FreeMemory(D, memory, &callbacks);
    }
    /* The driver map happens before the common mapping lock is acquired. If a
     * hooked mapper replaces the target while begin waits, the under-lock
     * ownership check must reject it without releasing the foreign mapping. */
    reset_model();
    memory = allocate_mali();
    replace_target_on_begin = 1;
    unsigned before = native_unmaps;
    assert(map_mali(memory, &out) == VK_ERROR_MEMORY_MAP_FAILED);
    assert(native_unmaps == before + 1 && !model_released && !guard_calls && !chunk_calls);
    assert(modeled_count == 2 && modeled[0].start == model_target &&
           modeled[0].end == model_target + model_length && modeled[0].kind == UNRELATED);
    assert(!get_memory(get_device(D), memory)->native && !model_native);
    l_FreeMemory(D, memory, &callbacks);
    /* A plain reservation and Wine's optional executable anonymous mapping
     * are valid placed targets too. Reserve-unmap deliberately yields NONE. */
    for (int executable = 0; executable < 2; executable++) {
        reset_model();
        model_target_protection = executable ? PROT_READ | PROT_WRITE | PROT_EXEC : PROT_NONE;
        modeled[0].protection = model_target_protection;
        memory = allocate_mali();
        assert(map_mali(memory, &out) == VK_SUCCESS);
        unmap.memory = memory;
        assert(l_UnmapMemory2KHR(D, &unmap) == VK_SUCCESS);
        assert_model_clean(0, PROT_NONE);
        l_FreeMemory(D, memory, &callbacks);
    }
    model_enabled = 0;
    teardown();
    mali_mode = 0;
    assert(native_maps == native_unmaps);
    assert(mapping_begins && mapping_begins == mapping_ends && !mapping_lock_depth);
    puts("PASS mali placement: Wine RW/RWX targets, failure protection restoration, "
         "exact 64K-aligned target, descending bounded chunks and offsets, "
         "guard/partial/wrong-address rollback, ambiguous fd rejection, concurrent VA preserved");
}
static void test_missing_mapping_api(void) {
    mali_mode = 1;
    setup(1);
    uint32_t count = 0;
    assert(l_EnumerateDeviceExtensionProperties(P, NULL, &count, NULL) == VK_SUCCESS);
    assert(count == 1);
    VkPhysicalDeviceMapMemoryPlacedFeaturesEXT placed = {
        .sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_MAP_MEMORY_PLACED_FEATURES_EXT};
    VkPhysicalDeviceFeatures2 features = {.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2,
                                         .pNext = &placed};
    l_GetPhysicalDeviceFeatures2(P, &features);
    assert(!placed.memoryMapPlaced && !placed.memoryUnmapReserve);
    create_device(0);
    assert(l_GetDeviceProcAddr(D, "vkCreateImage") == (PFN_vkVoidFunction)nai);
    assert(!l_GetDeviceProcAddr(D, "vkMapMemory2KHR"));
    teardown();
    mali_mode = 0;
    assert(!mapping_begins && !mapping_ends);
    puts("PASS missing mapping API: Mali placement disabled, PowerVR unaffected");
}
int main(int argc, char **argv) {
    if (argc == 2) {
        if (!strcmp(argv[1], "--missing-begin")) have_mapping_begin = 0;
        else if (!strcmp(argv[1], "--missing-end")) have_mapping_end = 0;
        else if (!strcmp(argv[1], "--fallback-api")) fallback_mapping_api = 1;
        else assert(!"unexpected test mode");
    } else assert(argc == 1);
    test_dispatch();
    test_map2_only();
    test_disabled_placed_features();
    test_resources();
    test_mapping();
    test_concurrency();
    if (!have_mapping_begin || !have_mapping_end) {
        test_missing_mapping_api();
        return 0;
    }
    test_mali_resources();
    test_mali_placement();
    assert(explicit_failures == 1);
    printf(
        "PASS total: callback allocations=%u frees=%u native_maps=%u unmaps=%u driver_frees=%u\n",
        allocs, frees, native_maps, native_unmaps, driver_frees);
    return 0;
}
