/*
 * VK_LAYER_DROIDDECK_mali_compat: makes up for what Mali lacks and DXVK requires.
 *
 * DXVK refuses any GPU without fillModeNonSolid, multiViewport, shaderClipDistance,
 * shaderCullDistance, textureCompressionBC and robustBufferAccess2, and Mali has none of them.
 * The layer reports each missing feature as supported, keeps it out of the driver's device, and
 * handles what uses it: polygon modes draw filled, only the first viewport is kept, clip distances
 * become a varying the fragment shader discards on, cull distances are dropped, and BC textures
 * are decoded on the CPU into uncompressed images as they are uploaded.
 */
#ifndef MALI_COMPAT_H
#define MALI_COMPAT_H

#include <pthread.h>
#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>
#include <vulkan/vk_layer.h>
#include <vulkan/vulkan.h>

enum gap {
    GAP_FILL_MODE = 1u << 0,
    GAP_MULTI_VIEWPORT = 1u << 1,
    GAP_CLIP_DISTANCE = 1u << 2,
    GAP_CULL_DISTANCE = 1u << 3,
    GAP_ROBUSTNESS2 = 1u << 4,
    GAP_BC = 1u << 5,
    /* draw.c and pipeline.c (added for Mali-G57 and drivers without these, on top of David Cimo's six) */
    GAP_MULTI_DRAW = 1u << 6,     /* multiDrawIndirect: indirect draws looped one by one */
    GAP_DEPTH_CLIP = 1u << 7,     /* VK_EXT_depth_clip_enable: depthClipEnable off becomes depth clamp */
    GAP_NULL_DESCRIPTOR = 1u << 8, /* VK_EXT_robustness2 missing entirely: nullDescriptor stand-ins */
};

#define GAPS_SHADER (GAP_CLIP_DISTANCE | GAP_CULL_DISTANCE)
#define GAPS_PIPELINE (GAP_FILL_MODE | GAP_MULTI_VIEWPORT | GAPS_SHADER | GAP_DEPTH_CLIP)

void layer_log(const char *format, ...) __attribute__((format(printf, 1, 2)));

/* Open addressing from a Vulkan handle to a pointer. Not locked: the owner holds its lock. */
struct handle_map {
    uint64_t *keys;
    void **values;
    uint32_t capacity;
    uint32_t count;
};

void *map_get(const struct handle_map *map, uint64_t key);
void map_put(struct handle_map *map, uint64_t key, void *value);
void *map_remove(struct handle_map *map, uint64_t key);
void map_free(struct handle_map *map);

struct physical_device {
    VkPhysicalDevice handle;
    uint32_t gaps;
};

struct instance {
    void *key;
    VkInstance handle;
    bool active;
    PFN_vkGetInstanceProcAddr gipa;
    PFN_vkDestroyInstance DestroyInstance;
    PFN_vkEnumerateDeviceExtensionProperties EnumerateDeviceExtensionProperties;
    PFN_vkGetPhysicalDeviceFeatures GetPhysicalDeviceFeatures;
    PFN_vkGetPhysicalDeviceFeatures2 GetPhysicalDeviceFeatures2;
    PFN_vkGetPhysicalDeviceProperties GetPhysicalDeviceProperties;
    PFN_vkGetPhysicalDeviceProperties2 GetPhysicalDeviceProperties2;
    PFN_vkGetPhysicalDeviceMemoryProperties GetPhysicalDeviceMemoryProperties;
    PFN_vkGetPhysicalDeviceFormatProperties GetPhysicalDeviceFormatProperties;
    PFN_vkGetPhysicalDeviceFormatProperties2 GetPhysicalDeviceFormatProperties2;
    PFN_vkGetPhysicalDeviceImageFormatProperties GetPhysicalDeviceImageFormatProperties;
    PFN_vkGetPhysicalDeviceImageFormatProperties2 GetPhysicalDeviceImageFormatProperties2;
    PFN_vkEnumeratePhysicalDevices EnumeratePhysicalDevices;
    PFN_vkGetPhysicalDeviceMemoryProperties2 GetPhysicalDeviceMemoryProperties2;
    PFN_vkGetPhysicalDeviceQueueFamilyProperties GetPhysicalDeviceQueueFamilyProperties;
    PFN_vkGetPhysicalDeviceQueueFamilyProperties2 GetPhysicalDeviceQueueFamilyProperties2;
    PFN_vkGetPhysicalDeviceExternalBufferProperties GetPhysicalDeviceExternalBufferProperties;
    PFN_vkGetPhysicalDeviceExternalSemaphoreProperties GetPhysicalDeviceExternalSemaphoreProperties;
    PFN_vkGetPhysicalDeviceExternalFenceProperties GetPhysicalDeviceExternalFenceProperties;
    pthread_mutex_t lock;
    struct physical_device physical_devices[8];
    uint32_t physical_device_count;
    struct instance *next;
};

#define DEVICE_FUNCTIONS(X) \
    X(DestroyDevice) \
    X(CreateShaderModule) \
    X(DestroyShaderModule) \
    X(CreateGraphicsPipelines) \
    X(CmdSetViewport) \
    X(CmdSetScissor) \
    X(CmdSetViewportWithCount) \
    X(CmdSetScissorWithCount) \
    X(CreateImage) \
    X(DestroyImage) \
    X(CreateImageView) \
    X(GetDeviceImageMemoryRequirements) \
    X(GetDeviceImageSparseMemoryRequirements) \
    X(GetDeviceImageSubresourceLayout) \
    X(CmdCopyBufferToImage) \
    X(CmdCopyBufferToImage2) \
    X(CmdCopyImageToBuffer) \
    X(CmdCopyImageToBuffer2) \
    X(CmdCopyImage) \
    X(CmdCopyImage2) \
    X(CreateBuffer) \
    X(DestroyBuffer) \
    X(GetBufferMemoryRequirements) \
    X(BindBufferMemory) \
    X(BindBufferMemory2) \
    X(AllocateMemory) \
    X(FreeMemory) \
    X(MapMemory) \
    X(MapMemory2) \
    X(UnmapMemory) \
    X(UnmapMemory2) \
    X(AllocateCommandBuffers) \
    X(FreeCommandBuffers) \
    X(BeginCommandBuffer) \
    X(ResetCommandBuffer) \
    X(ResetCommandPool) \
    X(DestroyCommandPool) \
    X(CmdDrawIndirect) \
    X(CmdDrawIndexedIndirect) \
    X(GetImageMemoryRequirements) \
    X(BindImageMemory) \
    X(DestroyImageView) \
    X(CreateBufferView) \
    X(DestroyBufferView) \
    X(UpdateDescriptorSets) \
    X(CmdPushDescriptorSet) \
    X(CreateDescriptorUpdateTemplate) \
    X(DestroyDescriptorUpdateTemplate) \
    X(UpdateDescriptorSetWithTemplate) \
    X(CmdPushDescriptorSetWithTemplate) \
    X(CmdBindVertexBuffers) \
    X(CmdBindVertexBuffers2)

struct staging_block;

struct device {
    void *key;
    VkDevice handle;
    PFN_vkGetDeviceProcAddr gdpa;
    /* The gaps the application enabled a made-up feature for. */
    uint32_t emulated;
    /* Locations a clip distance varying may use, from the vertex output and fragment input limits. */
    uint32_t max_varying_locations;
    VkPhysicalDeviceMemoryProperties memory_properties;
#define DECLARE(name) PFN_vk##name name;
    DEVICE_FUNCTIONS(DECLARE)
#undef DECLARE
    pthread_mutex_t lock;
    struct handle_map shader_modules;  /* VkShaderModule -> struct spirv_code, for modules with distances */
    struct handle_map images;          /* VkImage -> struct bc_image */
    struct handle_map buffers;         /* VkBuffer -> struct bound_buffer */
    struct handle_map memories;        /* VkDeviceMemory -> struct mapped_memory */
    struct handle_map command_buffers; /* VkCommandBuffer -> struct command_buffer */
    struct staging_block *free_staging;
    struct handle_map templates;       /* VkDescriptorUpdateTemplate -> struct template_info (draw.c) */
    /* nullDescriptor stand-ins (draw.c): 0 not made yet, 1 ready, -1 failed. */
    int null_ready;
    VkBuffer null_buffer;
    VkDeviceMemory null_buffer_memory;
    VkImage null_image;
    VkDeviceMemory null_image_memory;
    VkImageView null_view;
    /* Texel-buffer stand-ins: RGBA8 for uniform (sampled) texel buffers, R32_UINT for storage. */
    VkBufferView null_uniform_texel;
    VkBufferView null_storage_texel;
    struct device *next;
};

struct device *device_from_handle(const void *dispatchable);

struct spirv_code {
    uint32_t *words;
    size_t count;
};

/* spirv.c */
bool spirv_uses_distances(const uint32_t *words, size_t count);
/* The module with clip and cull distances taken out; false when it cannot be rewritten. */
bool spirv_strip_distances(const uint32_t *words, size_t count, struct spirv_code *out);

enum clip_role { CLIP_STRIP, CLIP_PRODUCER, CLIP_CONSUMER };

struct clip_info {
    bool has_clip;            /* a standalone ClipDistance variable this stage could hand on */
    uint32_t clip_count;      /* its array length */
    uint32_t locations_used;  /* one past the highest varying location, outputs or inputs per role */
};

void spirv_clip_info(const uint32_t *words, size_t count, enum clip_role role, struct clip_info *info);
/*
 * Producer: the last stage before rasterization writes its clip distances to `location` instead.
 * Consumer: the fragment shader reads them there and discards when any is negative.
 * Both drop cull distances. False when the module cannot be rewritten.
 */
bool spirv_emulate_clip(const uint32_t *words, size_t count, enum clip_role role, uint32_t location,
                        uint32_t clip_count, struct spirv_code *out);

/* pipeline.c */
VKAPI_ATTR VkResult VKAPI_CALL CreateShaderModule(VkDevice device, const VkShaderModuleCreateInfo *info,
                                                  const VkAllocationCallbacks *allocator, VkShaderModule *module);
VKAPI_ATTR void VKAPI_CALL DestroyShaderModule(VkDevice device, VkShaderModule module,
                                               const VkAllocationCallbacks *allocator);
VKAPI_ATTR VkResult VKAPI_CALL CreateGraphicsPipelines(VkDevice device, VkPipelineCache cache, uint32_t count,
                                                       const VkGraphicsPipelineCreateInfo *infos,
                                                       const VkAllocationCallbacks *allocator, VkPipeline *pipelines);
VKAPI_ATTR void VKAPI_CALL CmdSetViewport(VkCommandBuffer cmd, uint32_t first, uint32_t count,
                                          const VkViewport *viewports);
VKAPI_ATTR void VKAPI_CALL CmdSetScissor(VkCommandBuffer cmd, uint32_t first, uint32_t count,
                                         const VkRect2D *scissors);
VKAPI_ATTR void VKAPI_CALL CmdSetViewportWithCount(VkCommandBuffer cmd, uint32_t count,
                                                   const VkViewport *viewports);
VKAPI_ATTR void VKAPI_CALL CmdSetScissorWithCount(VkCommandBuffer cmd, uint32_t count, const VkRect2D *scissors);

/* bc.c */
VkFormat bc_substitute(VkFormat format);
VKAPI_ATTR void VKAPI_CALL GetPhysicalDeviceFormatProperties(VkPhysicalDevice physical_device, VkFormat format,
                                                             VkFormatProperties *properties);
VKAPI_ATTR void VKAPI_CALL GetPhysicalDeviceFormatProperties2(VkPhysicalDevice physical_device, VkFormat format,
                                                              VkFormatProperties2 *properties);
VKAPI_ATTR VkResult VKAPI_CALL GetPhysicalDeviceImageFormatProperties(
    VkPhysicalDevice physical_device, VkFormat format, VkImageType type, VkImageTiling tiling,
    VkImageUsageFlags usage, VkImageCreateFlags flags, VkImageFormatProperties *properties);
VKAPI_ATTR VkResult VKAPI_CALL GetPhysicalDeviceImageFormatProperties2(
    VkPhysicalDevice physical_device, const VkPhysicalDeviceImageFormatInfo2 *info,
    VkImageFormatProperties2 *properties);
VKAPI_ATTR VkResult VKAPI_CALL CreateImage(VkDevice device, const VkImageCreateInfo *info,
                                           const VkAllocationCallbacks *allocator, VkImage *image);
VKAPI_ATTR void VKAPI_CALL DestroyImage(VkDevice device, VkImage image, const VkAllocationCallbacks *allocator);
VKAPI_ATTR VkResult VKAPI_CALL CreateImageView(VkDevice device, const VkImageViewCreateInfo *info,
                                               const VkAllocationCallbacks *allocator, VkImageView *view);
VKAPI_ATTR void VKAPI_CALL GetDeviceImageMemoryRequirements(VkDevice device,
                                                            const VkDeviceImageMemoryRequirements *info,
                                                            VkMemoryRequirements2 *requirements);
VKAPI_ATTR void VKAPI_CALL GetDeviceImageSparseMemoryRequirements(
    VkDevice device, const VkDeviceImageMemoryRequirements *info, uint32_t *count,
    VkSparseImageMemoryRequirements2 *requirements);
VKAPI_ATTR void VKAPI_CALL GetDeviceImageSubresourceLayout(VkDevice device,
                                                           const VkDeviceImageSubresourceInfo *info,
                                                           VkSubresourceLayout2 *layout);
VKAPI_ATTR void VKAPI_CALL CmdCopyBufferToImage(VkCommandBuffer cmd, VkBuffer buffer, VkImage image,
                                                VkImageLayout layout, uint32_t count,
                                                const VkBufferImageCopy *regions);
VKAPI_ATTR void VKAPI_CALL CmdCopyBufferToImage2(VkCommandBuffer cmd, const VkCopyBufferToImageInfo2 *info);
VKAPI_ATTR void VKAPI_CALL CmdCopyImageToBuffer(VkCommandBuffer cmd, VkImage image, VkImageLayout layout,
                                                VkBuffer buffer, uint32_t count, const VkBufferImageCopy *regions);
VKAPI_ATTR void VKAPI_CALL CmdCopyImageToBuffer2(VkCommandBuffer cmd, const VkCopyImageToBufferInfo2 *info);
VKAPI_ATTR void VKAPI_CALL CmdCopyImage(VkCommandBuffer cmd, VkImage src, VkImageLayout src_layout, VkImage dst,
                                        VkImageLayout dst_layout, uint32_t count, const VkImageCopy *regions);
VKAPI_ATTR void VKAPI_CALL CmdCopyImage2(VkCommandBuffer cmd, const VkCopyImageInfo2 *info);
VKAPI_ATTR VkResult VKAPI_CALL BindBufferMemory(VkDevice device, VkBuffer buffer, VkDeviceMemory memory,
                                                VkDeviceSize offset);
VKAPI_ATTR VkResult VKAPI_CALL BindBufferMemory2(VkDevice device, uint32_t count,
                                                 const VkBindBufferMemoryInfo *infos);
VKAPI_ATTR void VKAPI_CALL DestroyBuffer(VkDevice device, VkBuffer buffer, const VkAllocationCallbacks *allocator);
VKAPI_ATTR void VKAPI_CALL FreeMemory(VkDevice device, VkDeviceMemory memory, const VkAllocationCallbacks *allocator);
VKAPI_ATTR VkResult VKAPI_CALL MapMemory(VkDevice device, VkDeviceMemory memory, VkDeviceSize offset,
                                         VkDeviceSize size, VkMemoryMapFlags flags, void **data);
VKAPI_ATTR VkResult VKAPI_CALL MapMemory2(VkDevice device, const VkMemoryMapInfo *info, void **data);
VKAPI_ATTR void VKAPI_CALL UnmapMemory(VkDevice device, VkDeviceMemory memory);
VKAPI_ATTR VkResult VKAPI_CALL UnmapMemory2(VkDevice device, const VkMemoryUnmapInfo *info);
VKAPI_ATTR VkResult VKAPI_CALL AllocateCommandBuffers(VkDevice device, const VkCommandBufferAllocateInfo *info,
                                                      VkCommandBuffer *buffers);
VKAPI_ATTR void VKAPI_CALL FreeCommandBuffers(VkDevice device, VkCommandPool pool, uint32_t count,
                                              const VkCommandBuffer *buffers);
VKAPI_ATTR VkResult VKAPI_CALL BeginCommandBuffer(VkCommandBuffer cmd, const VkCommandBufferBeginInfo *info);
VKAPI_ATTR VkResult VKAPI_CALL ResetCommandBuffer(VkCommandBuffer cmd, VkCommandBufferResetFlags flags);
VKAPI_ATTR VkResult VKAPI_CALL ResetCommandPool(VkDevice device, VkCommandPool pool, VkCommandPoolResetFlags flags);
VKAPI_ATTR void VKAPI_CALL DestroyCommandPool(VkDevice device, VkCommandPool pool,
                                              const VkAllocationCallbacks *allocator);
void bc_destroy_device(struct device *dev);

/* layer.c */
struct instance *instance_from_handle(const void *dispatchable);
/* What the GPU lacks of the features the layer can make up for. */
uint32_t physical_device_gaps(struct instance *inst, VkPhysicalDevice physical_device);

/* trace.c (DROIDDECK_MALI_COMPAT_TRACE=1) */
void trace_load(struct instance *inst);
PFN_vkVoidFunction trace_hook(struct instance *inst, const char *name);
void trace_extension_names(const VkExtensionProperties *ext, uint32_t n);


/* draw.c */
VKAPI_ATTR void VKAPI_CALL CmdDrawIndirect(VkCommandBuffer cmd, VkBuffer buffer, VkDeviceSize offset, uint32_t draw_count, uint32_t stride);
VKAPI_ATTR void VKAPI_CALL CmdDrawIndexedIndirect(VkCommandBuffer cmd, VkBuffer buffer, VkDeviceSize offset, uint32_t draw_count, uint32_t stride);
VKAPI_ATTR void VKAPI_CALL UpdateDescriptorSets(VkDevice device, uint32_t write_count, const VkWriteDescriptorSet *writes, uint32_t copy_count, const VkCopyDescriptorSet *copies);
VKAPI_ATTR void VKAPI_CALL CmdPushDescriptorSet(VkCommandBuffer cmd, VkPipelineBindPoint bind_point, VkPipelineLayout layout, uint32_t set, uint32_t write_count, const VkWriteDescriptorSet *writes);
VKAPI_ATTR VkResult VKAPI_CALL CreateDescriptorUpdateTemplate(VkDevice device, const VkDescriptorUpdateTemplateCreateInfo *info, const VkAllocationCallbacks *allocator, VkDescriptorUpdateTemplate *tmpl);
VKAPI_ATTR void VKAPI_CALL DestroyDescriptorUpdateTemplate(VkDevice device, VkDescriptorUpdateTemplate tmpl, const VkAllocationCallbacks *allocator);
VKAPI_ATTR void VKAPI_CALL UpdateDescriptorSetWithTemplate(VkDevice device, VkDescriptorSet set, VkDescriptorUpdateTemplate tmpl, const void *data);
VKAPI_ATTR void VKAPI_CALL CmdPushDescriptorSetWithTemplate(VkCommandBuffer cmd, VkDescriptorUpdateTemplate tmpl, VkPipelineLayout layout, uint32_t set, const void *data);
VKAPI_ATTR void VKAPI_CALL CmdBindVertexBuffers(VkCommandBuffer cmd, uint32_t first, uint32_t count, const VkBuffer *buffers, const VkDeviceSize *offsets);
VKAPI_ATTR void VKAPI_CALL CmdBindVertexBuffers2(VkCommandBuffer cmd, uint32_t first, uint32_t count, const VkBuffer *buffers, const VkDeviceSize *offsets, const VkDeviceSize *sizes, const VkDeviceSize *strides);
void null_resources_destroy(struct device *dev);
#endif
