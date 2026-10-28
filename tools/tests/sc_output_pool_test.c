#include <assert.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define AHARDWAREBUFFER_USAGE_GPU_COLOR_OUTPUT 0x200ULL
#define AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE 0x100ULL
#define AHARDWAREBUFFER_USAGE_COMPOSER_OVERLAY 0x800ULL
#define AHARDWAREBUFFER_USAGE_CPU_READ_RARELY 2ULL
#define AHB_RGBA8 1
#define AHB_RGB10A2 0x2b
#define MOD_LINEAR 0ULL
#define MOD_QCOM_COMPRESSED 0x0500000000000001ULL
#define DRM_ABGR2101010 0x30334241
#define DRM_ABGR8888 0x34324241
typedef struct { uint32_t width,height,layers,format; uint64_t usage; uint32_t stride; } AHardwareBuffer_Desc;
struct droiddeck_native_handle { int version, numFds, numInts; int data[4]; };
typedef struct AHardwareBuffer { AHardwareBuffer_Desc desc; struct droiddeck_native_handle handle; } AHardwareBuffer;
struct vkp_image { int unused; };
struct slot { AHardwareBuffer *ahb; struct vkp_image *img; int w,h; uint32_t fmt; int release_fd,busy; };
struct layer { const char *name; int alloc_tier, pool_n; uint64_t pool_modifier;
    int logged_w,logged_h,logged_tier; uint32_t logged_fmt; uint64_t logged_mod; };
static struct { const void *(*getNativeHandle)(const AHardwareBuffer *); } api;
static int native_supported, native_failure, dma_failure, allocation_failure, native_cpu_only, qti, bad_desc, bad_stride;
static int allocations,releases,native_imports,dma_imports,plain_requests,cpu_requests,ubwc_requests;
static AHardwareBuffer *latest;
static struct vkp_image image;
static int vkp_output_ahb_supported(void) { return native_supported; }
static void droiddeck_log(const char *kind, const char *fmt, ...) { (void)kind; (void)fmt; }
static int AHardwareBuffer_allocate(const AHardwareBuffer_Desc *desc, AHardwareBuffer **out) {
    allocations++;
    if (desc->usage & (1ULL << 28)) ubwc_requests++;
    else if (desc->usage & AHARDWAREBUFFER_USAGE_CPU_READ_RARELY) cpu_requests++;
    else plain_requests++;
    if (allocation_failure) return -1;
    AHardwareBuffer *b = calloc(1, sizeof(*b)); assert(b);
    b->desc = *desc; b->desc.stride = bad_stride ? UINT32_MAX : desc->width;
    if (bad_desc) b->desc.width++;
    b->handle.numFds = 1; b->handle.numInts = qti ? 2 : 1; b->handle.data[0] = 42;
    if (qti) {
        b->handle.data[1] = ('g' << 24) | ('m' << 16) | ('s' << 8) | 'm';
        b->handle.data[2] = desc->usage & (1ULL << 28) ? 0x08000000 : 0;
    }
    latest = b; *out = b; return 0;
}
static void AHardwareBuffer_describe(const AHardwareBuffer *b, AHardwareBuffer_Desc *d) { *d = b->desc; }
static const void *handle(const AHardwareBuffer *b) { return &b->handle; }
static void AHardwareBuffer_release(AHardwareBuffer *b) { releases++; free(b); }
static struct vkp_image *vkp_image_import_output_ahb(AHardwareBuffer *b, int w, int h, uint32_t fmt) {
    assert(b == latest && b->desc.width == (uint32_t)w && b->desc.height == (uint32_t)h && b->desc.format == fmt);
    native_imports++;
    return native_failure || (native_cpu_only && !(b->desc.usage & AHARDWAREBUFFER_USAGE_CPU_READ_RARELY)) ? NULL : &image;
}
static struct vkp_image *vkp_image_import_dmabuf(int fd, uint32_t fmt, uint64_t mod, int w, int h,
                                                uint32_t stride, uint32_t offset, int as_dst) {
    assert(fd == 42 && (fmt == DRM_ABGR8888 || fmt == DRM_ABGR2101010) && w == 1280 && h == 720);
    assert(stride == 1280 * 4 && offset == 0 && as_dst == 1);
    assert(mod == MOD_LINEAR || mod == MOD_QCOM_COMPRESSED);
    assert(qti || latest->desc.usage & AHARDWAREBUFFER_USAGE_CPU_READ_RARELY);
    dma_imports++; return dma_failure ? NULL : &image;
}
#include "sc_output_pool_under_test.inc"

static void reset(struct layer *l, struct slot *s) {
    *l = (struct layer){.name = "test", .pool_n = 3}; *s = (struct slot){0};
    api.getNativeHandle = handle;
    native_supported = 1;
    native_failure = dma_failure = allocation_failure = native_cpu_only = qti = bad_desc = bad_stride = 0;
    allocations = releases = native_imports = dma_imports = plain_requests = cpu_requests = ubwc_requests = 0;
}
static void accept(struct layer *l, struct slot *s, uint32_t fmt) {
    assert(alloc_slot(l,s,1280,720,fmt) == 0 && !g_alloc_failed);
    assert(s->ahb && s->img == &image && s->w == 1280 && s->h == 720 && s->fmt == fmt && s->release_fd == -1 && !s->busy);
    AHardwareBuffer_release(s->ahb);
    assert(releases == allocations);
}
int main(void) {
    struct layer l; struct slot s;
    reset(&l,&s); accept(&l,&s,AHB_RGBA8);
    assert(allocations == 1 && native_imports == 1 && !dma_imports && plain_requests == 1 && !cpu_requests && !ubwc_requests);
    assert(l.pool_modifier == MOD_GRALLOC && l.alloc_tier == TIER_PLAIN);
    reset(&l,&s); accept(&l,&s,AHB_RGB10A2); assert(l.pool_modifier == MOD_GRALLOC);
    reset(&l,&s); native_failure = 1; accept(&l,&s,AHB_RGBA8);
    assert(allocations == 2 && native_imports == 2 && dma_imports == 1 && plain_requests == 1 && cpu_requests == 1);
    assert(l.pool_modifier == MOD_LINEAR && l.alloc_tier == TIER_LINEAR);
    reset(&l,&s); native_cpu_only = 1; accept(&l,&s,AHB_RGBA8);
    assert(allocations == 2 && native_imports == 2 && !dma_imports && l.pool_modifier == MOD_GRALLOC);
    reset(&l,&s); native_supported = 0; accept(&l,&s,AHB_RGBA8);
    assert(allocations == 2 && !native_imports && dma_imports == 1 && ubwc_requests == 1 && cpu_requests == 1);
    reset(&l,&s); native_supported = 0; qti = 1; accept(&l,&s,AHB_RGBA8);
    assert(allocations == 1 && !native_imports && dma_imports == 1 && ubwc_requests == 1 && l.pool_modifier == MOD_QCOM_COMPRESSED);
    reset(&l,&s); native_failure = dma_failure = 1;
    assert(alloc_slot(&l,&s,1280,720,AHB_RGBA8) == -1 && g_alloc_failed);
    assert(allocations == 2 && releases == 2 && !s.ahb && !s.img);
    reset(&l,&s); allocation_failure = 1;
    assert(alloc_slot(&l,&s,1280,720,AHB_RGBA8) == -1 && g_alloc_failed);
    assert(allocations == 2 && !releases && !native_imports && !dma_imports);
    reset(&l,&s); bad_desc = 1;
    assert(alloc_slot(&l,&s,1280,720,AHB_RGBA8) == -1 && g_alloc_failed);
    assert(allocations == 1 && releases == 1 && !native_imports && !dma_imports);
    reset(&l,&s); native_supported = 0; bad_stride = 1;
    assert(alloc_slot(&l,&s,1280,720,AHB_RGBA8) == -1 && g_alloc_failed);
    assert(allocations == 2 && releases == 2 && !dma_imports);
    reset(&l,&s); assert(alloc_slot(&l,&s,0,720,AHB_RGBA8) == -1 && !allocations);
    reset(&l,&s); assert(alloc_slot(&l,&s,1280,720,0) == -1 && !allocations);
    return 0;
}
