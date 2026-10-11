/* SPDX-License-Identifier: GPL-3.0-or-later
 *
 * Surfaces and swapchains (backend-independent part).
 */
#define _GNU_SOURCE
#include "wsi.h"
#include "gen/vkb_client_gen.h"

#include <stdio.h>
#include <string.h>
#include <sys/mman.h>
#include <time.h>
#include <unistd.h>

const VkSurfaceFormatKHR vkb_wsi_formats[] = {
    {VK_FORMAT_B8G8R8A8_SRGB, VK_COLOR_SPACE_SRGB_NONLINEAR_KHR},
    {VK_FORMAT_B8G8R8A8_UNORM, VK_COLOR_SPACE_SRGB_NONLINEAR_KHR},
    {VK_FORMAT_R8G8B8A8_SRGB, VK_COLOR_SPACE_SRGB_NONLINEAR_KHR},
    {VK_FORMAT_R8G8B8A8_UNORM, VK_COLOR_SPACE_SRGB_NONLINEAR_KHR},
};
const uint32_t vkb_wsi_format_count = sizeof(vkb_wsi_formats) / sizeof(vkb_wsi_formats[0]);

#define FOURCC(a, b, c, d) ((uint32_t)(a) | ((uint32_t)(b) << 8) | ((uint32_t)(c) << 16) | ((uint32_t)(d) << 24))

uint32_t vkb_drm_format(VkFormat f)
{
    switch (f) {
    case VK_FORMAT_B8G8R8A8_SRGB:
    case VK_FORMAT_B8G8R8A8_UNORM: return FOURCC('A', 'R', '2', '4');
    case VK_FORMAT_R8G8B8A8_SRGB:
    case VK_FORMAT_R8G8B8A8_UNORM: return FOURCC('A', 'B', '2', '4');
    default: return 0;
    }
}

static vkb_surface *surf(VkSurfaceKHR s) { return (vkb_surface *)(uintptr_t)s; }
static vkb_swapchain *chain(VkSwapchainKHR s) { return (vkb_swapchain *)(uintptr_t)s; }

/* ------------------------------------------------------------------ surfaces */

static VkResult new_surface(enum vkb_surface_kind kind, VkSurfaceKHR *out, vkb_surface **ps)
{
    vkb_surface *s = calloc(1, sizeof(*s));
    if (!s) return VK_ERROR_OUT_OF_HOST_MEMORY;
    s->kind = kind;
    *ps = s;
    *out = (VkSurfaceKHR)(uintptr_t)s;
    return VK_SUCCESS;
}

VKAPI_ATTR VkResult VKAPI_CALL vkb_ep_vkCreateWaylandSurfaceKHR(VkInstance instance, const VkWaylandSurfaceCreateInfoKHR *pCreateInfo,
                                                               const VkAllocationCallbacks *pAllocator, VkSurfaceKHR *pSurface)
{
    (void)instance;
    (void)pAllocator;
    if (!vkb_wl_load()) return VK_ERROR_INITIALIZATION_FAILED;
    vkb_surface *s;
    VkResult r = new_surface(VKB_SURF_WAYLAND, pSurface, &s);
    if (r == VK_SUCCESS) {
        s->wl_display = pCreateInfo->display;
        s->wl_surface = pCreateInfo->surface;
    }
    return r;
}

VKAPI_ATTR VkResult VKAPI_CALL vkb_ep_vkCreateXcbSurfaceKHR(VkInstance instance, const VkXcbSurfaceCreateInfoKHR *pCreateInfo,
                                                           const VkAllocationCallbacks *pAllocator, VkSurfaceKHR *pSurface)
{
    (void)instance;
    (void)pAllocator;
    if (!vkb_xcb_load()) return VK_ERROR_INITIALIZATION_FAILED;
    vkb_surface *s;
    VkResult r = new_surface(VKB_SURF_XCB, pSurface, &s);
    if (r == VK_SUCCESS) {
        s->xcb_conn = pCreateInfo->connection;
        s->xcb_window = pCreateInfo->window;
    }
    return r;
}

VKAPI_ATTR VkResult VKAPI_CALL vkb_ep_vkCreateXlibSurfaceKHR(VkInstance instance, const VkXlibSurfaceCreateInfoKHR *pCreateInfo,
                                                            const VkAllocationCallbacks *pAllocator, VkSurfaceKHR *pSurface)
{
    (void)instance;
    (void)pAllocator;
    if (!vkb_xcb_load()) return VK_ERROR_INITIALIZATION_FAILED;
    void *conn = vkb_xlib_to_xcb(pCreateInfo->dpy);
    if (!conn) return VK_ERROR_INITIALIZATION_FAILED;
    vkb_surface *s;
    VkResult r = new_surface(VKB_SURF_XCB, pSurface, &s);
    if (r == VK_SUCCESS) {
        s->xcb_conn = conn;
        s->xcb_window = (uint32_t)pCreateInfo->window;
    }
    return r;
}

VKAPI_ATTR VkResult VKAPI_CALL vkb_ep_vkCreateHeadlessSurfaceEXT(VkInstance instance, const VkHeadlessSurfaceCreateInfoEXT *pCreateInfo,
                                                                const VkAllocationCallbacks *pAllocator, VkSurfaceKHR *pSurface)
{
    (void)instance;
    (void)pCreateInfo;
    (void)pAllocator;
    vkb_surface *s;
    return new_surface(VKB_SURF_HEADLESS, pSurface, &s);
}

VKAPI_ATTR void VKAPI_CALL vkb_ep_vkDestroySurfaceKHR(VkInstance instance, VkSurfaceKHR surface, const VkAllocationCallbacks *pAllocator)
{
    (void)instance;
    (void)pAllocator;
    free(surf(surface));
}

VKAPI_ATTR VkBool32 VKAPI_CALL vkb_ep_vkGetPhysicalDeviceWaylandPresentationSupportKHR(VkPhysicalDevice physicalDevice, uint32_t queueFamilyIndex,
                                                                                      struct wl_display *display)
{
    (void)physicalDevice;
    (void)queueFamilyIndex;
    (void)display;
    return vkb_wl_load() ? VK_TRUE : VK_FALSE;
}

VKAPI_ATTR VkBool32 VKAPI_CALL vkb_ep_vkGetPhysicalDeviceXcbPresentationSupportKHR(VkPhysicalDevice physicalDevice, uint32_t queueFamilyIndex,
                                                                                  xcb_connection_t *connection, xcb_visualid_t visual_id)
{
    (void)physicalDevice;
    (void)queueFamilyIndex;
    (void)connection;
    (void)visual_id;
    return vkb_xcb_load() ? VK_TRUE : VK_FALSE;
}

VKAPI_ATTR VkBool32 VKAPI_CALL vkb_ep_vkGetPhysicalDeviceXlibPresentationSupportKHR(VkPhysicalDevice physicalDevice, uint32_t queueFamilyIndex,
                                                                                   Display *dpy, VisualID visualID)
{
    (void)physicalDevice;
    (void)queueFamilyIndex;
    (void)dpy;
    (void)visualID;
    return vkb_xcb_load() ? VK_TRUE : VK_FALSE;
}

VKAPI_ATTR VkResult VKAPI_CALL vkb_ep_vkGetPhysicalDeviceSurfaceSupportKHR(VkPhysicalDevice physicalDevice, uint32_t queueFamilyIndex,
                                                                          VkSurfaceKHR surface, VkBool32 *pSupported)
{
    (void)surface;
    /* Presentation is a copy or a hand-over after the queue's work: any queue that can do
     * graphics or compute (or transfer) can present. */
    uint32_t n = 0;
    vkb_wire_vkGetPhysicalDeviceQueueFamilyProperties(physicalDevice, &n, NULL);
    VkQueueFamilyProperties *qp = calloc(n ? n : 1, sizeof(*qp));
    vkb_wire_vkGetPhysicalDeviceQueueFamilyProperties(physicalDevice, &n, qp);
    *pSupported = queueFamilyIndex < n &&
                  (qp[queueFamilyIndex].queueFlags & (VK_QUEUE_GRAPHICS_BIT | VK_QUEUE_COMPUTE_BIT | VK_QUEUE_TRANSFER_BIT));
    free(qp);
    return VK_SUCCESS;
}

static VkResult surface_extent(vkb_surface *s, VkExtent2D *e)
{
    switch (s->kind) {
    case VKB_SURF_WAYLAND: return vkb_wl_surface_extent(s, e);
    case VKB_SURF_XCB: return vkb_xcb_surface_extent(s, e);
    default:
        e->width = e->height = 0xFFFFFFFFu;
        return VK_SUCCESS;
    }
}

static VkImageUsageFlags supported_usage(VkPhysicalDevice pd)
{
    VkImageUsageFlags u = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT |
                          VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_INPUT_ATTACHMENT_BIT;
    VkFormatProperties fp;
    vkb_wire_vkGetPhysicalDeviceFormatProperties(pd, VK_FORMAT_B8G8R8A8_UNORM, &fp);
    if (fp.optimalTilingFeatures & VK_FORMAT_FEATURE_STORAGE_IMAGE_BIT) u |= VK_IMAGE_USAGE_STORAGE_BIT;
    return u;
}

VKAPI_ATTR VkResult VKAPI_CALL vkb_ep_vkGetPhysicalDeviceSurfaceCapabilitiesKHR(VkPhysicalDevice physicalDevice, VkSurfaceKHR surface,
                                                                               VkSurfaceCapabilitiesKHR *c)
{
    vkb_surface *s = surf(surface);
    memset(c, 0, sizeof(*c));
    VkResult r = surface_extent(s, &c->currentExtent);
    if (r != VK_SUCCESS) return r;
    c->minImageCount = s->kind == VKB_SURF_WAYLAND ? 3 : 2;
    c->maxImageCount = 8;
    c->minImageExtent = (VkExtent2D){1, 1};
    c->maxImageExtent = (VkExtent2D){16384, 16384};
    if (c->currentExtent.width != 0xFFFFFFFFu) {
        c->minImageExtent = c->maxImageExtent = c->currentExtent;
        if (!c->currentExtent.width || !c->currentExtent.height) c->minImageExtent = (VkExtent2D){0, 0};
    }
    c->maxImageArrayLayers = 1;
    c->supportedTransforms = VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR;
    c->currentTransform = VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR;
    c->supportedCompositeAlpha = VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR | VK_COMPOSITE_ALPHA_INHERIT_BIT_KHR;
    if (s->kind == VKB_SURF_WAYLAND) c->supportedCompositeAlpha |= VK_COMPOSITE_ALPHA_PRE_MULTIPLIED_BIT_KHR;
    c->supportedUsageFlags = supported_usage(physicalDevice);
    return VK_SUCCESS;
}

VKAPI_ATTR VkResult VKAPI_CALL vkb_ep_vkGetPhysicalDeviceSurfaceCapabilities2KHR(VkPhysicalDevice physicalDevice,
                                                                                const VkPhysicalDeviceSurfaceInfo2KHR *pInfo,
                                                                                VkSurfaceCapabilities2KHR *pCaps)
{
    VkResult r = vkb_ep_vkGetPhysicalDeviceSurfaceCapabilitiesKHR(physicalDevice, pInfo->surface, &pCaps->surfaceCapabilities);
    for (VkBaseOutStructure *b = (VkBaseOutStructure *)pCaps->pNext; b; b = b->pNext) {
        if (b->sType == VK_STRUCTURE_TYPE_SURFACE_PROTECTED_CAPABILITIES_KHR)
            ((VkSurfaceProtectedCapabilitiesKHR *)b)->supportsProtected = VK_FALSE;
    }
    return r;
}

VKAPI_ATTR VkResult VKAPI_CALL vkb_ep_vkGetPhysicalDeviceSurfaceFormatsKHR(VkPhysicalDevice physicalDevice, VkSurfaceKHR surface,
                                                                          uint32_t *pCount, VkSurfaceFormatKHR *pFormats)
{
    (void)physicalDevice;
    (void)surface;
    if (!pFormats) {
        *pCount = vkb_wsi_format_count;
        return VK_SUCCESS;
    }
    uint32_t n = *pCount < vkb_wsi_format_count ? *pCount : vkb_wsi_format_count;
    memcpy(pFormats, vkb_wsi_formats, n * sizeof(*pFormats));
    *pCount = n;
    return n < vkb_wsi_format_count ? VK_INCOMPLETE : VK_SUCCESS;
}

VKAPI_ATTR VkResult VKAPI_CALL vkb_ep_vkGetPhysicalDeviceSurfaceFormats2KHR(VkPhysicalDevice physicalDevice,
                                                                           const VkPhysicalDeviceSurfaceInfo2KHR *pInfo, uint32_t *pCount,
                                                                           VkSurfaceFormat2KHR *pFormats)
{
    (void)physicalDevice;
    (void)pInfo;
    if (!pFormats) {
        *pCount = vkb_wsi_format_count;
        return VK_SUCCESS;
    }
    uint32_t n = *pCount < vkb_wsi_format_count ? *pCount : vkb_wsi_format_count;
    for (uint32_t i = 0; i < n; i++) pFormats[i].surfaceFormat = vkb_wsi_formats[i];
    *pCount = n;
    return n < vkb_wsi_format_count ? VK_INCOMPLETE : VK_SUCCESS;
}

static const VkPresentModeKHR present_modes[] = {VK_PRESENT_MODE_FIFO_KHR, VK_PRESENT_MODE_MAILBOX_KHR, VK_PRESENT_MODE_IMMEDIATE_KHR};

VKAPI_ATTR VkResult VKAPI_CALL vkb_ep_vkGetPhysicalDeviceSurfacePresentModesKHR(VkPhysicalDevice physicalDevice, VkSurfaceKHR surface,
                                                                               uint32_t *pCount, VkPresentModeKHR *pModes)
{
    (void)physicalDevice;
    (void)surface;
    uint32_t total = 3;
    if (!pModes) {
        *pCount = total;
        return VK_SUCCESS;
    }
    uint32_t n = *pCount < total ? *pCount : total;
    memcpy(pModes, present_modes, n * sizeof(*pModes));
    *pCount = n;
    return n < total ? VK_INCOMPLETE : VK_SUCCESS;
}

VKAPI_ATTR VkResult VKAPI_CALL vkb_ep_vkGetPhysicalDevicePresentRectanglesKHR(VkPhysicalDevice physicalDevice, VkSurfaceKHR surface,
                                                                             uint32_t *pRectCount, VkRect2D *pRects)
{
    (void)physicalDevice;
    if (!pRects) {
        *pRectCount = 1;
        return VK_SUCCESS;
    }
    if (*pRectCount < 1) return VK_INCOMPLETE;
    VkExtent2D e;
    surface_extent(surf(surface), &e);
    if (e.width == 0xFFFFFFFFu) e = (VkExtent2D){16384, 16384};
    pRects[0] = (VkRect2D){{0, 0}, e};
    *pRectCount = 1;
    return VK_SUCCESS;
}

VKAPI_ATTR VkResult VKAPI_CALL vkb_ep_vkGetDeviceGroupPresentCapabilitiesKHR(VkDevice device, VkDeviceGroupPresentCapabilitiesKHR *pCaps)
{
    (void)device;
    memset(pCaps->presentMask, 0, sizeof(pCaps->presentMask));
    pCaps->presentMask[0] = 1;
    pCaps->modes = VK_DEVICE_GROUP_PRESENT_MODE_LOCAL_BIT_KHR;
    return VK_SUCCESS;
}

VKAPI_ATTR VkResult VKAPI_CALL vkb_ep_vkGetDeviceGroupSurfacePresentModesKHR(VkDevice device, VkSurfaceKHR surface,
                                                                            VkDeviceGroupPresentModeFlagsKHR *pModes)
{
    (void)device;
    (void)surface;
    *pModes = VK_DEVICE_GROUP_PRESENT_MODE_LOCAL_BIT_KHR;
    return VK_SUCCESS;
}

/* ------------------------------------------------------------------ swapchain images */

static uint32_t pick_type(vkb_device *dev, uint32_t bits, VkMemoryPropertyFlags want)
{
    const VkPhysicalDeviceMemoryProperties *mp = &dev->pd->client_mem;
    for (uint32_t i = 0; i < mp->memoryTypeCount; i++)
        if ((bits & (1u << i)) && (mp->memoryTypes[i].propertyFlags & want) == want) return i;
    for (uint32_t i = 0; i < mp->memoryTypeCount; i++)
        if (bits & (1u << i)) return i;
    return UINT32_MAX;
}

static int pd_has_ext(vkb_physdev *pd, const char *name)
{
    for (uint32_t i = 0; i < pd->next; i++)
        if (!strcmp(pd->exts[i].extensionName, name)) return 1;
    return 0;
}

/* One swapchain image: exported as a linear dma-buf when `dmabuf`, else an ordinary image. */
static VkResult create_image(vkb_swapchain *sc, vkb_swap_image *img, int dmabuf)
{
    vkb_device *dev = sc->dev;
    VkDevice device = (VkDevice)dev;
    const VkSwapchainCreateInfoKHR *ci = &sc->info;
    VkImageCreateInfo ici = {VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO};
    ici.imageType = VK_IMAGE_TYPE_2D;
    ici.format = ci->imageFormat;
    ici.extent = (VkExtent3D){ci->imageExtent.width, ci->imageExtent.height, 1};
    ici.mipLevels = 1;
    ici.arrayLayers = ci->imageArrayLayers;
    ici.samples = VK_SAMPLE_COUNT_1_BIT;
    ici.tiling = VK_IMAGE_TILING_OPTIMAL;
    ici.usage = ci->imageUsage | (sc->copy_present || !dmabuf ? VK_IMAGE_USAGE_TRANSFER_SRC_BIT : 0);
    ici.sharingMode = ci->imageSharingMode;
    ici.queueFamilyIndexCount = ci->queueFamilyIndexCount;
    ici.pQueueFamilyIndices = ci->pQueueFamilyIndices;
    ici.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    if (ci->flags & VK_SWAPCHAIN_CREATE_MUTABLE_FORMAT_BIT_KHR) ici.flags |= VK_IMAGE_CREATE_MUTABLE_FORMAT_BIT;
    VkImageFormatListCreateInfo fl = {VK_STRUCTURE_TYPE_IMAGE_FORMAT_LIST_CREATE_INFO, NULL, sc->view_format_count, sc->view_formats};
    VkExternalMemoryImageCreateInfo ext = {VK_STRUCTURE_TYPE_EXTERNAL_MEMORY_IMAGE_CREATE_INFO, NULL,
                                           VK_EXTERNAL_MEMORY_HANDLE_TYPE_DMA_BUF_BIT_EXT};
    uint64_t linear = 0; /* DRM_FORMAT_MOD_LINEAR */
    VkImageDrmFormatModifierListCreateInfoEXT mods = {VK_STRUCTURE_TYPE_IMAGE_DRM_FORMAT_MODIFIER_LIST_CREATE_INFO_EXT, NULL, 1, &linear};
    const void *chain = NULL;
    if (sc->view_format_count) {
        fl.pNext = chain;
        chain = &fl;
    }
    int use_mods = 0;
    if (dmabuf) {
        ext.pNext = chain;
        chain = &ext;
        if (pd_has_ext(dev->pd, "VK_EXT_image_drm_format_modifier")) {
            mods.pNext = chain;
            chain = &mods;
            ici.tiling = VK_IMAGE_TILING_DRM_FORMAT_MODIFIER_EXT;
            use_mods = 1;
        } else {
            ici.tiling = VK_IMAGE_TILING_LINEAR;
        }
    }
    ici.pNext = chain;
    VkResult r = vkb_wire_vkCreateImage(device, &ici, NULL, &img->image);
    if (r != VK_SUCCESS) return r;
    VkMemoryRequirements req;
    vkb_ep_vkGetImageMemoryRequirements(device, img->image, &req);
    uint32_t type = pick_type(dev, req.memoryTypeBits, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
    if (type == UINT32_MAX) return VK_ERROR_OUT_OF_DEVICE_MEMORY;
    VkMemoryDedicatedAllocateInfo ded = {VK_STRUCTURE_TYPE_MEMORY_DEDICATED_ALLOCATE_INFO, NULL, img->image, VK_NULL_HANDLE};
    VkExportMemoryAllocateInfo exp = {VK_STRUCTURE_TYPE_EXPORT_MEMORY_ALLOCATE_INFO, &ded, VK_EXTERNAL_MEMORY_HANDLE_TYPE_DMA_BUF_BIT_EXT};
    VkMemoryAllocateInfo ai = {VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO, dmabuf ? (const void *)&exp : (const void *)&ded, req.size, type};
    r = vkb_ep_vkAllocateMemory(device, &ai, NULL, &img->memory);
    if (r != VK_SUCCESS) return r;
    r = vkb_wire_vkBindImageMemory(device, img->image, img->memory, 0);
    if (r != VK_SUCCESS) return r;
    img->dmabuf_fd = -1;
    if (dmabuf) {
        VkMemoryGetFdInfoKHR gi = {VK_STRUCTURE_TYPE_MEMORY_GET_FD_INFO_KHR, NULL, img->memory, VK_EXTERNAL_MEMORY_HANDLE_TYPE_DMA_BUF_BIT_EXT};
        r = vkb_wire_vkGetMemoryFdKHR(device, &gi, &img->dmabuf_fd);
        if (r != VK_SUCCESS || img->dmabuf_fd < 0) return r != VK_SUCCESS ? r : VK_ERROR_INITIALIZATION_FAILED;
        VkImageSubresource sub = {use_mods ? VK_IMAGE_ASPECT_MEMORY_PLANE_0_BIT_EXT : VK_IMAGE_ASPECT_COLOR_BIT, 0, 0};
        VkSubresourceLayout lay;
        vkb_wire_vkGetImageSubresourceLayout(device, img->image, &sub, &lay);
        img->stride = (uint32_t)lay.rowPitch;
        img->offset = (uint32_t)lay.offset;
        img->modifier = 0;
        if (use_mods) {
            VkImageDrmFormatModifierPropertiesEXT mp = {VK_STRUCTURE_TYPE_IMAGE_DRM_FORMAT_MODIFIER_PROPERTIES_EXT};
            if (vkb_wire_vkGetImageDrmFormatModifierPropertiesEXT(device, img->image, &mp) == VK_SUCCESS) img->modifier = mp.drmFormatModifier;
        }
    }
    return VK_SUCCESS;
}

static void destroy_image(vkb_swapchain *sc, vkb_swap_image *img)
{
    VkDevice device = (VkDevice)sc->dev;
    if (img->dmabuf_fd >= 0) close(img->dmabuf_fd);
    if (img->image) vkb_ep_vkDestroyImage(device, img->image, NULL);
    if (img->memory) vkb_ep_vkFreeMemory(device, img->memory, NULL);
}

static VkResult create_staging(vkb_swapchain *sc)
{
    vkb_device *dev = sc->dev;
    VkDevice device = (VkDevice)dev;
    VkDeviceSize size = (VkDeviceSize)sc->info.imageExtent.width * sc->info.imageExtent.height * 4;
    VkBufferCreateInfo bci = {VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO, NULL, 0, size, VK_BUFFER_USAGE_TRANSFER_DST_BIT,
                              VK_SHARING_MODE_EXCLUSIVE, 0, NULL};
    VkResult r = vkb_ep_vkCreateBuffer(device, &bci, NULL, &sc->staging);
    if (r != VK_SUCCESS) return r;
    VkMemoryRequirements req;
    vkb_ep_vkGetBufferMemoryRequirements(device, sc->staging, &req);
    uint32_t type = pick_type(dev, req.memoryTypeBits,
                              VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT | VK_MEMORY_PROPERTY_HOST_CACHED_BIT);
    if (type == UINT32_MAX || !(dev->pd->client_mem.memoryTypes[type].propertyFlags & VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT))
        type = pick_type(dev, req.memoryTypeBits, VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT);
    if (type == UINT32_MAX || !(dev->pd->client_mem.memoryTypes[type].propertyFlags & VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT)) {
        VKB_ERR("swapchain: no host-visible memory to read frames back into");
        return VK_ERROR_OUT_OF_HOST_MEMORY;
    }
    VkMemoryAllocateInfo ai = {VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO, NULL, req.size, type};
    r = vkb_ep_vkAllocateMemory(device, &ai, NULL, &sc->staging_mem);
    if (r != VK_SUCCESS) return r;
    r = vkb_wire_vkBindBufferMemory(device, sc->staging, sc->staging_mem, 0);
    if (r != VK_SUCCESS) return r;
    void *p = NULL;
    r = vkb_ep_vkMapMemory(device, sc->staging_mem, 0, VK_WHOLE_SIZE, 0, &p);
    sc->staging_ptr = p;
    sc->staging_size = size;
    return r;
}

static VkResult ensure_copy_cbs(vkb_swapchain *sc, uint32_t family)
{
    VkDevice device = (VkDevice)sc->dev;
    if (sc->pool && sc->pool_family == family) return VK_SUCCESS;
    if (sc->pool) {
        vkb_ep_vkDestroyCommandPool(device, sc->pool, NULL);
        sc->pool = VK_NULL_HANDLE;
    }
    VkCommandPoolCreateInfo pci = {VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO, NULL, 0, family};
    VkResult r = vkb_wire_vkCreateCommandPool(device, &pci, NULL, &sc->pool);
    if (r != VK_SUCCESS) return r;
    sc->pool_family = family;
    VkCommandBufferAllocateInfo cai = {VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO, NULL, sc->pool, VK_COMMAND_BUFFER_LEVEL_PRIMARY, sc->count};
    r = vkb_ep_vkAllocateCommandBuffers(device, &cai, sc->copy_cbs);
    if (r != VK_SUCCESS) return r;
    for (uint32_t i = 0; i < sc->count; i++) {
        VkCommandBuffer cb = sc->copy_cbs[i];
        VkCommandBufferBeginInfo bi = {VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO, NULL, 0, NULL};
        vkb_ep_vkBeginCommandBuffer(cb, &bi);
        VkImageMemoryBarrier b = {VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER, NULL, VK_ACCESS_MEMORY_WRITE_BIT, VK_ACCESS_TRANSFER_READ_BIT,
                                  VK_IMAGE_LAYOUT_PRESENT_SRC_KHR, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, VK_QUEUE_FAMILY_IGNORED,
                                  VK_QUEUE_FAMILY_IGNORED, sc->images[i].image, {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1}};
        vkb_wire_vkCmdPipelineBarrier(cb, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, 0, 0, NULL, 0, NULL, 1, &b);
        VkBufferImageCopy region = {0, 0, 0, {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1}, {0, 0, 0},
                                    {sc->info.imageExtent.width, sc->info.imageExtent.height, 1}};
        vkb_wire_vkCmdCopyImageToBuffer(cb, sc->images[i].image, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, sc->staging, 1, &region);
        b.srcAccessMask = VK_ACCESS_TRANSFER_READ_BIT;
        b.dstAccessMask = 0;
        b.oldLayout = VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;
        b.newLayout = VK_IMAGE_LAYOUT_PRESENT_SRC_KHR;
        VkMemoryBarrier hb = {VK_STRUCTURE_TYPE_MEMORY_BARRIER, NULL, VK_ACCESS_TRANSFER_WRITE_BIT, VK_ACCESS_HOST_READ_BIT};
        vkb_wire_vkCmdPipelineBarrier(cb, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_HOST_BIT | VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, 0, 1,
                                      &hb, 0, NULL, 1, &b);
        r = vkb_ep_vkEndCommandBuffer(cb);
        if (r != VK_SUCCESS) return r;
    }
    return VK_SUCCESS;
}

/* Waits (on the CPU) for the app's rendering into image `index`, reading it back when needed. */
VkResult vkb_wsi_wait_rendering(vkb_swapchain *sc, VkQueue queue, uint32_t index, uint32_t nwait, const VkSemaphore *wait)
{
    VkDevice device = (VkDevice)sc->dev;
    VkPipelineStageFlags *stages = alloca((nwait ? nwait : 1) * sizeof(VkPipelineStageFlags));
    for (uint32_t i = 0; i < nwait; i++) stages[i] = VK_PIPELINE_STAGE_ALL_COMMANDS_BIT;
    VkSubmitInfo si = {VK_STRUCTURE_TYPE_SUBMIT_INFO, NULL, nwait, wait, stages, 0, NULL, 0, NULL};
    if (sc->copy_present) {
        VkResult r = ensure_copy_cbs(sc, ((vkb_queue *)queue)->family);
        if (r != VK_SUCCESS) return r;
        si.commandBufferCount = 1;
        si.pCommandBuffers = &sc->copy_cbs[index];
    }
    VkResult r = vkb_wire_vkQueueSubmit(queue, 1, &si, sc->fence);
    if (r != VK_SUCCESS) return r;
    r = vkb_wire_vkWaitForFences(device, 1, &sc->fence, VK_TRUE, 10ull * 1000 * 1000 * 1000);
    vkb_wire_vkResetFences(device, 1, &sc->fence);
    if (r == VK_TIMEOUT) {
        VKB_ERR("swapchain: a frame took more than 10 s to render");
        return VK_ERROR_DEVICE_LOST;
    }
    return r;
}

/* ------------------------------------------------------------------ swapchain */

static void swapchain_free(vkb_swapchain *sc)
{
    VkDevice device = (VkDevice)sc->dev;
    if (sc->backend && sc->backend->destroy) sc->backend->destroy(sc);
    for (uint32_t i = 0; i < sc->count; i++) destroy_image(sc, &sc->images[i]);
    if (sc->pool) vkb_ep_vkDestroyCommandPool(device, sc->pool, NULL);
    if (sc->fence) vkb_wire_vkDestroyFence(device, sc->fence, NULL);
    if (sc->staging) vkb_ep_vkDestroyBuffer(device, sc->staging, NULL);
    if (sc->staging_mem) vkb_ep_vkFreeMemory(device, sc->staging_mem, NULL);
    free(sc->images);
    free(sc->copy_cbs);
    free(sc->view_formats);
    pthread_mutex_destroy(&sc->lock);
    free(sc);
}

static VkResult swapchain_build(vkb_swapchain *sc, int dmabuf)
{
    VkDevice device = (VkDevice)sc->dev;
    sc->dmabuf = dmabuf;
    for (uint32_t i = 0; i < sc->count; i++) {
        VkResult r = create_image(sc, &sc->images[i], dmabuf);
        if (r != VK_SUCCESS) return r;
        sc->images[i].chain = sc;
    }
    if (sc->copy_present) {
        VkResult r = create_staging(sc);
        if (r != VK_SUCCESS) return r;
    }
    VkFenceCreateInfo fci = {VK_STRUCTURE_TYPE_FENCE_CREATE_INFO};
    VkResult r = vkb_wire_vkCreateFence(device, &fci, NULL, &sc->fence);
    if (r != VK_SUCCESS) return r;
    return sc->backend->init(sc);
}

static void swapchain_teardown(vkb_swapchain *sc)
{
    VkDevice device = (VkDevice)sc->dev;
    if (sc->backend && sc->backend->destroy) sc->backend->destroy(sc);
    sc->bdata = NULL;
    for (uint32_t i = 0; i < sc->count; i++) {
        destroy_image(sc, &sc->images[i]);
        memset(&sc->images[i], 0, sizeof(sc->images[i]));
        sc->images[i].dmabuf_fd = -1;
    }
    if (sc->fence) vkb_wire_vkDestroyFence(device, sc->fence, NULL);
    sc->fence = VK_NULL_HANDLE;
    if (sc->staging) vkb_ep_vkDestroyBuffer(device, sc->staging, NULL);
    if (sc->staging_mem) vkb_ep_vkFreeMemory(device, sc->staging_mem, NULL);
    sc->staging = VK_NULL_HANDLE;
    sc->staging_mem = VK_NULL_HANDLE;
    sc->staging_ptr = NULL;
}

VKAPI_ATTR VkResult VKAPI_CALL vkb_ep_vkCreateSwapchainKHR(VkDevice device, const VkSwapchainCreateInfoKHR *pCreateInfo,
                                                          const VkAllocationCallbacks *pAllocator, VkSwapchainKHR *pSwapchain)
{
    (void)pAllocator;
    vkb_device *dev = vkb_dev(device);
    vkb_surface *s = surf(pCreateInfo->surface);
    if (pCreateInfo->oldSwapchain) chain(pCreateInfo->oldSwapchain)->retired = 1;
    vkb_swapchain *sc = calloc(1, sizeof(*sc));
    if (!sc) return VK_ERROR_OUT_OF_HOST_MEMORY;
    pthread_mutex_init(&sc->lock, NULL);
    sc->dev = dev;
    sc->surface = s;
    sc->info = *pCreateInfo;
    sc->info.pNext = NULL;
    sc->info.pQueueFamilyIndices = NULL;
    sc->info.queueFamilyIndexCount = 0;
    sc->info.imageSharingMode = VK_SHARING_MODE_EXCLUSIVE;
    for (const VkBaseInStructure *b = pCreateInfo->pNext; b; b = b->pNext) {
        if (b->sType == VK_STRUCTURE_TYPE_IMAGE_FORMAT_LIST_CREATE_INFO) {
            const VkImageFormatListCreateInfo *fl = (const void *)b;
            sc->view_formats = calloc(fl->viewFormatCount + 1, sizeof(VkFormat));
            memcpy(sc->view_formats, fl->pViewFormats, fl->viewFormatCount * sizeof(VkFormat));
            sc->view_format_count = fl->viewFormatCount;
        }
    }
    sc->count = pCreateInfo->minImageCount < 2 ? 2 : pCreateInfo->minImageCount;
    if (s->kind == VKB_SURF_WAYLAND && sc->count < 3) sc->count = 3;
    sc->images = calloc(sc->count, sizeof(vkb_swap_image));
    sc->copy_cbs = calloc(sc->count, sizeof(VkCommandBuffer));
    for (uint32_t i = 0; i < sc->count; i++) sc->images[i].dmabuf_fd = -1;
    VkResult r;
    switch (s->kind) {
    case VKB_SURF_WAYLAND:
        sc->backend = &vkb_wsi_wayland_backend;
        sc->copy_present = getenv("VKBRIDGE_WSI_SHM") != NULL;
        r = swapchain_build(sc, !sc->copy_present);
        if (r != VK_SUCCESS && !sc->copy_present) {
            VKB_WARN("Wayland: no dma-buf presentation (%d); falling back to copies through shared memory", r);
            swapchain_teardown(sc);
            sc->copy_present = 1;
            r = swapchain_build(sc, 0);
        }
        break;
    case VKB_SURF_XCB:
        sc->backend = &vkb_wsi_x11_backend;
        sc->copy_present = 1;
        r = swapchain_build(sc, 0);
        break;
    default:
        sc->backend = &vkb_wsi_headless_backend;
        r = swapchain_build(sc, 0);
        break;
    }
    if (r != VK_SUCCESS) {
        VKB_ERR("swapchain creation failed: %d", r);
        swapchain_free(sc);
        return r;
    }
    VKB_INFO("swapchain %ux%u format %d, %u images, %s", sc->info.imageExtent.width, sc->info.imageExtent.height, sc->info.imageFormat,
             sc->count, sc->copy_present ? "presented by copy" : sc->dmabuf ? "presented as dma-bufs" : "headless");
    *pSwapchain = (VkSwapchainKHR)(uintptr_t)sc;
    return VK_SUCCESS;
}

VKAPI_ATTR void VKAPI_CALL vkb_ep_vkDestroySwapchainKHR(VkDevice device, VkSwapchainKHR swapchain, const VkAllocationCallbacks *pAllocator)
{
    (void)pAllocator;
    if (!swapchain) return;
    vkb_swapchain *sc = chain(swapchain);
    /* Images may still be in use by the GPU (present waits are CPU-side, so not by us). */
    vkb_wire_vkDeviceWaitIdle(device);
    swapchain_free(sc);
}

VKAPI_ATTR VkResult VKAPI_CALL vkb_ep_vkGetSwapchainImagesKHR(VkDevice device, VkSwapchainKHR swapchain, uint32_t *pCount, VkImage *pImages)
{
    (void)device;
    vkb_swapchain *sc = chain(swapchain);
    if (!pImages) {
        *pCount = sc->count;
        return VK_SUCCESS;
    }
    uint32_t n = *pCount < sc->count ? *pCount : sc->count;
    for (uint32_t i = 0; i < n; i++) pImages[i] = sc->images[i].image;
    *pCount = n;
    return n < sc->count ? VK_INCOMPLETE : VK_SUCCESS;
}

/* Signals the app's semaphore/fence for an acquired image (no GPU work is involved). */
static VkResult signal_acquire(vkb_device *dev, VkSemaphore sem, VkFence fence)
{
    VkDevice device = (VkDevice)dev;
    if (sem) {
        VkImportSemaphoreFdInfoKHR si = {VK_STRUCTURE_TYPE_IMPORT_SEMAPHORE_FD_INFO_KHR, NULL, sem, VK_SEMAPHORE_IMPORT_TEMPORARY_BIT,
                                         VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_SYNC_FD_BIT, -1};
        VkResult r = vkb_wire_vkImportSemaphoreFdKHR(device, &si);
        if (r != VK_SUCCESS) {
            /* No sync-fd import: signal it with an empty submit. */
            if (!dev->queues) return VK_ERROR_SURFACE_LOST_KHR;
            VkSubmitInfo s = {VK_STRUCTURE_TYPE_SUBMIT_INFO, NULL, 0, NULL, NULL, 0, NULL, 1, &sem};
            r = vkb_wire_vkQueueSubmit((VkQueue)dev->queues, 1, &s, fence);
            return r;
        }
    }
    if (fence) {
        VkImportFenceFdInfoKHR fi = {VK_STRUCTURE_TYPE_IMPORT_FENCE_FD_INFO_KHR, NULL, fence, VK_FENCE_IMPORT_TEMPORARY_BIT,
                                     VK_EXTERNAL_FENCE_HANDLE_TYPE_SYNC_FD_BIT, -1};
        VkResult r = vkb_wire_vkImportFenceFdKHR(device, &fi);
        if (r != VK_SUCCESS) {
            if (!dev->queues) return VK_ERROR_SURFACE_LOST_KHR;
            r = vkb_wire_vkQueueSubmit((VkQueue)dev->queues, 0, NULL, fence);
            return r;
        }
    }
    return VK_SUCCESS;
}

static uint64_t now_ns(void)
{
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (uint64_t)ts.tv_sec * 1000000000ull + (uint64_t)ts.tv_nsec;
}

VKAPI_ATTR VkResult VKAPI_CALL vkb_ep_vkAcquireNextImageKHR(VkDevice device, VkSwapchainKHR swapchain, uint64_t timeout, VkSemaphore semaphore,
                                                           VkFence fence, uint32_t *pImageIndex)
{
    vkb_swapchain *sc = chain(swapchain);
    if (sc->retired) return VK_ERROR_OUT_OF_DATE_KHR;
    if (sc->status != VK_SUCCESS) return sc->status;
    uint64_t deadline = timeout == UINT64_MAX ? UINT64_MAX : now_ns() + timeout;
    for (;;) {
        pthread_mutex_lock(&sc->lock);
        for (uint32_t i = 0; i < sc->count; i++) {
            if (!sc->images[i].busy && !sc->images[i].acquired) {
                sc->images[i].acquired = 1;
                pthread_mutex_unlock(&sc->lock);
                VkResult r = signal_acquire(vkb_dev(device), semaphore, fence);
                if (r != VK_SUCCESS) return r;
                *pImageIndex = i;
                return sc->status;
            }
        }
        pthread_mutex_unlock(&sc->lock);
        uint64_t now = now_ns();
        if (timeout == 0) return VK_NOT_READY;
        if (now >= deadline) return VK_TIMEOUT;
        VkResult r = sc->backend->wait_free(sc, deadline == UINT64_MAX ? UINT64_MAX : deadline - now);
        if (r < 0) {
            sc->status = r;
            return r;
        }
    }
}

VKAPI_ATTR VkResult VKAPI_CALL vkb_ep_vkAcquireNextImage2KHR(VkDevice device, const VkAcquireNextImageInfoKHR *pInfo, uint32_t *pImageIndex)
{
    return vkb_ep_vkAcquireNextImageKHR(device, pInfo->swapchain, pInfo->timeout, pInfo->semaphore, pInfo->fence, pImageIndex);
}

/* VKBRIDGE_WSI_DUMP=/path/prefix: writes presented frame 60 as a PPM (debugging aid). */
static void dump_frame(vkb_swapchain *sc, uint32_t index)
{
    /* The 60th present, or every VKBRIDGE_WSI_DUMP_EVERY-th (watching a game get somewhere). */
    static long every = -1;
    if (every < 0) every = getenv("VKBRIDGE_WSI_DUMP_EVERY") ? atol(getenv("VKBRIDGE_WSI_DUMP_EVERY")) : 0;
    if (every > 0 ? sc->presents % every != 0 : sc->presents != 60) return;
    uint32_t w = sc->info.imageExtent.width, h = sc->info.imageExtent.height, stride = w * 4;
    const uint8_t *px = NULL;
    void *map = NULL;
    size_t maplen = 0;
    if (sc->copy_present) {
        px = sc->staging_ptr;
    } else if (sc->images[index].dmabuf_fd >= 0) {
        stride = sc->images[index].stride;
        maplen = (size_t)sc->images[index].offset + (size_t)stride * h;
        map = mmap(NULL, maplen, PROT_READ, MAP_SHARED, sc->images[index].dmabuf_fd, 0);
        if (map == MAP_FAILED) return;
        px = (const uint8_t *)map + sc->images[index].offset;
    }
    if (!px) return;
    char path[512];
    if (every > 0)
        snprintf(path, sizeof(path), "%s-%d-%06u.ppm", getenv("VKBRIDGE_WSI_DUMP"), (int)getpid(), (unsigned)sc->presents);
    else
        snprintf(path, sizeof(path), "%s-%d.ppm", getenv("VKBRIDGE_WSI_DUMP"), (int)getpid());
    FILE *f = fopen(path, "wb");
    if (f) {
        int bgr = sc->info.imageFormat == VK_FORMAT_B8G8R8A8_UNORM || sc->info.imageFormat == VK_FORMAT_B8G8R8A8_SRGB;
        fprintf(f, "P6 %u %u 255\n", w, h);
        for (uint32_t y = 0; y < h; y++)
            for (uint32_t x = 0; x < w; x++) {
                const uint8_t *p = px + (size_t)y * stride + x * 4;
                uint8_t rgb[3] = {p[bgr ? 2 : 0], p[1], p[bgr ? 0 : 2]};
                fwrite(rgb, 1, 3, f);
            }
        fclose(f);
        VKB_INFO("dumped frame to %s", path);
    }
    if (map) munmap(map, maplen);
}

VKAPI_ATTR VkResult VKAPI_CALL vkb_ep_vkQueuePresentKHR(VkQueue queue, const VkPresentInfoKHR *pPresentInfo)
{
    const VkPresentRegionsKHR *regions = NULL;
    for (const VkBaseInStructure *b = pPresentInfo->pNext; b; b = b->pNext)
        if (b->sType == VK_STRUCTURE_TYPE_PRESENT_REGIONS_KHR) regions = (const void *)b;
    VkResult worst = VK_SUCCESS;
    for (uint32_t i = 0; i < pPresentInfo->swapchainCount; i++) {
        vkb_swapchain *sc = chain(pPresentInfo->pSwapchains[i]);
        uint32_t index = pPresentInfo->pImageIndices[i];
        VkResult r = sc->status;
        if (index >= sc->count) r = VK_ERROR_OUT_OF_DATE_KHR;
        if (r >= 0) {
            r = vkb_wsi_wait_rendering(sc, queue, index, i == 0 ? pPresentInfo->waitSemaphoreCount : 0, pPresentInfo->pWaitSemaphores);
        }
        pthread_mutex_lock(&sc->lock);
        if (index < sc->count) {
            sc->images[index].acquired = 0;
            sc->images[index].busy = sc->dmabuf && !sc->copy_present;
        }
        pthread_mutex_unlock(&sc->lock);
        if (r >= 0 && getenv("VKBRIDGE_WSI_DUMP")) dump_frame(sc, index);
        if (r >= 0) {
            r = sc->backend->present(sc, index, regions && i < regions->swapchainCount ? &regions->pRegions[i] : NULL);
            sc->presents++;
        }
        if (r < 0) {
            pthread_mutex_lock(&sc->lock);
            if (index < sc->count) sc->images[index].busy = 0;
            pthread_mutex_unlock(&sc->lock);
            if (r == VK_ERROR_OUT_OF_DATE_KHR || r == VK_ERROR_SURFACE_LOST_KHR) sc->status = r;
        }
        if (r == VK_SUCCESS && sc->status == VK_SUBOPTIMAL_KHR) r = VK_SUBOPTIMAL_KHR;
        if (pPresentInfo->pResults) pPresentInfo->pResults[i] = r;
        if (r < 0 && (worst >= 0 || r == VK_ERROR_DEVICE_LOST)) worst = r;
        else if (worst == VK_SUCCESS && r == VK_SUBOPTIMAL_KHR) worst = r;
    }
    return worst;
}

/* ------------------------------------------------------------------ headless */

static VkResult hl_init(vkb_swapchain *sc) { (void)sc; return VK_SUCCESS; }
static VkResult hl_present(vkb_swapchain *sc, uint32_t i, const VkPresentRegionKHR *r) { (void)sc; (void)i; (void)r; return VK_SUCCESS; }
static VkResult hl_wait(vkb_swapchain *sc, uint64_t t) { (void)sc; (void)t; return VK_TIMEOUT; }
static void hl_destroy(vkb_swapchain *sc) { (void)sc; }
const vkb_wsi_backend vkb_wsi_headless_backend = {hl_init, hl_present, hl_wait, hl_destroy};
