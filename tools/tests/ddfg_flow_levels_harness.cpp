#include "framegen.hpp"
#include <algorithm>
#include <cassert>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <map>
#include <memory>
#include <string>
#include <vector>

namespace {

constexpr int levels = 7;
constexpr VkImageUsageFlags flow_usage =
    VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT;
enum Pipeline { LUMA, DOWNSAMPLE, FLOW3, FLOW4, EXPAND3, EXPAND4, GLOBAL_MOTION, PREWARP, FLOW_REG, SYNTH };
struct Image { VkExtent3D extent; VkFormat format; VkImageUsageFlags usage; };
struct Allocation { VkDeviceSize size; std::unique_ptr<uint8_t[]> data; };
struct Binding { VkDescriptorType type; VkImageView view; VkBuffer buffer; VkDeviceSize range; };
struct Dispatch { unsigned pipeline; uint32_t x, y, z; };
std::map<VkImage, Image> images;
std::map<VkImageView, VkImage> views;
std::map<VkBuffer, VkBufferCreateInfo> buffers;
std::map<VkDeviceMemory, Allocation> allocations;
std::map<VkBuffer, VkDeviceMemory> buffer_memory;
std::map<VkDescriptorSet, std::map<uint32_t, Binding>> descriptors;
std::map<VkPipeline, unsigned> pipelines;
std::map<VkImage, unsigned> image_ids;
std::map<VkBuffer, unsigned> buffer_ids;
std::vector<Dispatch> dispatches;
uintptr_t serial = 100;
unsigned buffer_creates, set_creates;
VkPipeline bound_pipeline;
VkDescriptorSet bound_set;
uint64_t command_hash = 14695981039346656037ull;

template<class T> T handle() { return reinterpret_cast<T>(++serial); }

void hash(uint64_t value) {
    for (unsigned i = 0; i < 8; i++) {
        command_hash = (command_hash ^ (value & 255)) * 1099511628211ull;
        value >>= 8;
    }
}

void hash_image(VkImage image) {
    assert(images.count(image));
    if (!image_ids.count(image)) image_ids[image] = static_cast<unsigned>(image_ids.size() + 1);
    const auto &info = images.at(image);
    hash(image_ids.at(image));
    hash(info.extent.width); hash(info.extent.height); hash(info.format); hash(info.usage);
}

template<class Info, class Out>
VkResult VKAPI_CALL create(VkDevice, const Info *, const VkAllocationCallbacks *, Out *out) {
    *out = handle<Out>();
    return VK_SUCCESS;
}
template<class T> void VKAPI_CALL destroy(VkDevice, T, const VkAllocationCallbacks *) {}

VkResult VKAPI_CALL create_image(VkDevice, const VkImageCreateInfo *info,
                                 const VkAllocationCallbacks *, VkImage *out) {
    *out = handle<VkImage>();
    images[*out] = {info->extent, info->format, info->usage};
    return VK_SUCCESS;
}
VkResult VKAPI_CALL create_view(VkDevice, const VkImageViewCreateInfo *info,
                                const VkAllocationCallbacks *, VkImageView *out) {
    assert(images.count(info->image));
    *out = handle<VkImageView>();
    views[*out] = info->image;
    return VK_SUCCESS;
}
void VKAPI_CALL destroy_image(VkDevice, VkImage image, const VkAllocationCallbacks *) {
    assert(images.erase(image) == 1);
}
void VKAPI_CALL destroy_view(VkDevice, VkImageView view, const VkAllocationCallbacks *) {
    assert(views.erase(view) == 1);
}
VkResult VKAPI_CALL create_buffer(VkDevice, const VkBufferCreateInfo *info,
                                  const VkAllocationCallbacks *, VkBuffer *out) {
    *out = handle<VkBuffer>();
    buffers[*out] = *info;
    buffer_creates++;
    return VK_SUCCESS;
}
void VKAPI_CALL destroy_buffer(VkDevice, VkBuffer buffer, const VkAllocationCallbacks *) {
    assert(buffers.erase(buffer) == 1);
    buffer_memory.erase(buffer);
}
void VKAPI_CALL image_requirements(VkDevice, VkImage image, VkMemoryRequirements *out) {
    const auto &info = images.at(image);
    const uint64_t bytes = info.format == VK_FORMAT_R32_SFLOAT ? 4 : 8;
    *out = {uint64_t(info.extent.width) * info.extent.height * bytes, 1, 1};
}
void VKAPI_CALL buffer_requirements(VkDevice, VkBuffer buffer, VkMemoryRequirements *out) {
    *out = {buffers.at(buffer).size, 1, 1};
}
VkResult VKAPI_CALL allocate(VkDevice, const VkMemoryAllocateInfo *info,
                             const VkAllocationCallbacks *, VkDeviceMemory *out) {
    *out = handle<VkDeviceMemory>();
    allocations[*out].size = info->allocationSize;
    return VK_SUCCESS;
}
void VKAPI_CALL free_memory(VkDevice, VkDeviceMemory memory, const VkAllocationCallbacks *) {
    assert(allocations.erase(memory) == 1);
}
VkResult VKAPI_CALL bind_image(VkDevice, VkImage image, VkDeviceMemory memory, VkDeviceSize) {
    assert(images.count(image) && allocations.count(memory));
    return VK_SUCCESS;
}
VkResult VKAPI_CALL bind_buffer(VkDevice, VkBuffer buffer, VkDeviceMemory memory, VkDeviceSize) {
    assert(buffers.count(buffer) && allocations.count(memory));
    buffer_memory[buffer] = memory;
    return VK_SUCCESS;
}
VkResult VKAPI_CALL map_memory(VkDevice, VkDeviceMemory memory, VkDeviceSize offset,
                               VkDeviceSize size, VkMemoryMapFlags, void **out) {
    auto &allocation = allocations.at(memory);
    assert(offset == 0 && size <= allocation.size);
    allocation.data = std::make_unique<uint8_t[]>(allocation.size);
    *out = allocation.data.get();
    return VK_SUCCESS;
}
void VKAPI_CALL unmap(VkDevice, VkDeviceMemory memory) { assert(allocations.count(memory)); }
VkResult VKAPI_CALL idle(VkDevice) { return VK_SUCCESS; }
void VKAPI_CALL memory_properties(VkPhysicalDevice, VkPhysicalDeviceMemoryProperties *out) {
    *out = {};
    out->memoryTypeCount = 1;
    out->memoryTypes[0].propertyFlags = VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT |
        VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT;
}
VkResult VKAPI_CALL allocate_sets(VkDevice, const VkDescriptorSetAllocateInfo *info, VkDescriptorSet *out) {
    for (uint32_t i = 0; i < info->descriptorSetCount; i++) {
        out[i] = handle<VkDescriptorSet>();
        descriptors[out[i]] = {};
        set_creates++;
    }
    return VK_SUCCESS;
}
VkResult VKAPI_CALL reset_pool(VkDevice, VkDescriptorPool, VkDescriptorPoolResetFlags) {
    descriptors.clear();
    return VK_SUCCESS;
}
void VKAPI_CALL update_sets(VkDevice, uint32_t count, const VkWriteDescriptorSet *writes,
                            uint32_t, const VkCopyDescriptorSet *) {
    for (uint32_t i = 0; i < count; i++) {
        const auto &write = writes[i];
        assert(descriptors.count(write.dstSet) && write.descriptorCount == 1);
        Binding binding{write.descriptorType, VK_NULL_HANDLE, VK_NULL_HANDLE, 0};
        if (write.pImageInfo) {
            binding.view = write.pImageInfo->imageView;
            assert(views.count(binding.view));
        } else {
            binding.buffer = write.pBufferInfo->buffer;
            binding.range = write.pBufferInfo->range;
            assert(buffers.count(binding.buffer));
        }
        descriptors.at(write.dstSet)[write.dstBinding] = binding;
    }
}
VkResult VKAPI_CALL create_pipelines(VkDevice, VkPipelineCache, uint32_t count,
                                     const VkComputePipelineCreateInfo *, const VkAllocationCallbacks *,
                                     VkPipeline *out) {
    for (uint32_t i = 0; i < count; i++) {
        const auto index = static_cast<unsigned>(pipelines.size());
        out[i] = handle<VkPipeline>();
        pipelines[out[i]] = index;
    }
    return VK_SUCCESS;
}
void VKAPI_CALL bind_pipeline(VkCommandBuffer, VkPipelineBindPoint, VkPipeline pipeline) {
    assert(pipelines.count(pipeline));
    bound_pipeline = pipeline;
}
void VKAPI_CALL bind_sets(VkCommandBuffer, VkPipelineBindPoint, VkPipelineLayout,
                          uint32_t, uint32_t count, const VkDescriptorSet *sets,
                          uint32_t, const uint32_t *) {
    assert(count == 1 && descriptors.count(sets[0]));
    bound_set = sets[0];
}
void VKAPI_CALL dispatch(VkCommandBuffer, uint32_t x, uint32_t y, uint32_t z) {
    const unsigned pipeline = pipelines.at(bound_pipeline);
    dispatches.push_back({pipeline, x, y, z});
    hash(1); hash(pipeline); hash(x); hash(y); hash(z);
    for (const auto &[slot, binding] : descriptors.at(bound_set)) {
        hash(slot); hash(binding.type);
        if (binding.view) {
            hash_image(views.at(binding.view));
        } else {
            if (!buffer_ids.count(binding.buffer))
                buffer_ids[binding.buffer] = static_cast<unsigned>(buffer_ids.size() + 1);
            const auto &allocation = allocations.at(buffer_memory.at(binding.buffer));
            const auto size = binding.range == VK_WHOLE_SIZE ? allocation.size : binding.range;
            assert(allocation.data && size <= allocation.size);
            hash(buffer_ids.at(binding.buffer)); hash(size);
            for (VkDeviceSize i = 0; i < size; i++) hash(allocation.data[i]);
        }
    }
}
void VKAPI_CALL barrier(VkCommandBuffer, VkPipelineStageFlags src, VkPipelineStageFlags dst,
                        VkDependencyFlags flags, uint32_t nm, const VkMemoryBarrier *memory,
                        uint32_t nb, const VkBufferMemoryBarrier *, uint32_t ni,
                        const VkImageMemoryBarrier *image) {
    assert(nb == 0);
    hash(2); hash(src); hash(dst); hash(flags); hash(nm); hash(ni);
    for (uint32_t i = 0; i < nm; i++) {
        hash(memory[i].srcAccessMask); hash(memory[i].dstAccessMask);
    }
    for (uint32_t i = 0; i < ni; i++) {
        hash_image(image[i].image);
        hash(image[i].oldLayout); hash(image[i].newLayout);
        hash(image[i].srcAccessMask); hash(image[i].dstAccessMask);
        hash(image[i].srcQueueFamilyIndex); hash(image[i].dstQueueFamilyIndex);
    }
}
void VKAPI_CALL clear_image(VkCommandBuffer, VkImage image, VkImageLayout layout,
                            const VkClearColorValue *color, uint32_t count, const VkImageSubresourceRange *) {
    assert(count == 1);
    hash(3); hash_image(image); hash(layout);
    for (unsigned i = 0; i < 4; i++) hash(color->uint32[i]);
}
void VKAPI_CALL copy_image(VkCommandBuffer, VkImage src, VkImageLayout sl, VkImage dst,
                           VkImageLayout dl, uint32_t count, const VkImageCopy *region) {
    assert(count == 1);
    hash(4); hash_image(src); hash(sl); hash_image(dst); hash(dl);
    hash(region->extent.width); hash(region->extent.height); hash(region->extent.depth);
}

uint32_t dimension(uint32_t full, int level) { return std::max(full >> level, 1u); }

void check_images(uint32_t width, uint32_t height, int finest) {
    assert(images.size() == static_cast<size_t>(32 - 2 * finest));
    for (int level = finest; level < levels; level++) {
        const auto w = dimension(width, level), h = dimension(height, level);
        assert(std::any_of(images.begin(), images.end(), [=](const auto &entry) {
            const auto &image = entry.second;
            return image.format == VK_FORMAT_R16G16B16A16_SFLOAT && image.usage == flow_usage &&
                image.extent.width == w && image.extent.height == h;
        }));
    }
}

VkImageView external_image(uint32_t width, uint32_t height) {
    VkImage image = handle<VkImage>();
    VkImageView view = handle<VkImageView>();
    images[image] = {{width, height, 1}, VK_FORMAT_R8G8B8A8_UNORM,
                     VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_STORAGE_BIT};
    views[view] = image;
    return view;
}

void record(ddfg::FrameGen &fg, int model, uint32_t width, uint32_t height, unsigned generations) {
    const auto previous = external_image(width, height), current = external_image(width, height);
    const auto first = dispatches.size();
    for (unsigned i = 0; i < generations; i++)
        fg.record(reinterpret_cast<VkCommandBuffer>(1), previous, current, external_image(width, height),
                  float(i + 1) / float(generations + 1), 0, i, i != 0);
    std::vector<Dispatch> expected;
    auto add = [&](unsigned pipeline, int level) {
        expected.push_back({pipeline, (dimension(width, level) + 15) / 16,
                            (dimension(height, level) + 15) / 16, 1});
    };
    add(LUMA, 0); add(LUMA, 0);
    for (int level = 1; level < levels; level++) { add(DOWNSAMPLE, level); add(DOWNSAMPLE, level); }
    expected.push_back({GLOBAL_MOTION, 8, 1, 1});
    for (int level = fg.flowFinest(); level < levels; level++) add(PREWARP, level);
    for (int level = levels - 1; level >= fg.flowFinest(); level--) add(model == 4 ? FLOW4 : FLOW3, level);
    for (int i = 0; i < 4; i++) add(FLOW_REG, fg.flowFinest());
    add(model == 4 ? EXPAND4 : EXPAND3, 0);
    for (unsigned i = 0; i < generations; i++) add(SYNTH, 0);
    assert(dispatches.size() - first == expected.size());
    for (size_t i = 0; i < expected.size(); i++) {
        const auto &actual = dispatches[first + i], &want = expected[i];
        assert(actual.pipeline == want.pipeline && actual.x == want.x && actual.y == want.y && actual.z == want.z);
    }
    for (auto it = views.begin(); it != views.end();) {
        if (images.at(it->second).format == VK_FORMAT_R8G8B8A8_UNORM) {
            images.erase(it->second);
            it = views.erase(it);
        } else ++it;
    }
}

}

int main(int argc, char **argv) {
    assert(argc == 8);
    const std::string mode = argv[1];
    const int model = std::atoi(argv[2]), preset = std::atoi(argv[3]);
    uint32_t width = static_cast<uint32_t>(std::atoi(argv[4]));
    uint32_t height = static_cast<uint32_t>(std::atoi(argv[5]));
    const unsigned generations = static_cast<unsigned>(std::atoi(argv[6]));
    const unsigned repeats = static_cast<unsigned>(std::atoi(argv[7]));
    assert((model == 3 || model == 4) && preset >= 0 && preset <= 2);
    assert(width && height && generations >= 1 && generations <= 3 && repeats > 0);
    ddfg::DeviceDispatch dd;
    ddfg::InstanceDispatch id;
    id.GetPhysicalDeviceMemoryProperties = memory_properties;
    dd.CreateImage = create_image; dd.DestroyImage = destroy_image;
    dd.CreateImageView = create_view; dd.DestroyImageView = destroy_view;
    dd.CreateBuffer = create_buffer; dd.DestroyBuffer = destroy_buffer;
    dd.GetImageMemoryRequirements = image_requirements; dd.GetBufferMemoryRequirements = buffer_requirements;
    dd.AllocateMemory = allocate; dd.FreeMemory = free_memory;
    dd.BindImageMemory = bind_image; dd.BindBufferMemory = bind_buffer;
    dd.MapMemory = map_memory; dd.UnmapMemory = unmap; dd.DeviceWaitIdle = idle;
    dd.CreateSampler = create<VkSamplerCreateInfo, VkSampler>; dd.DestroySampler = destroy<VkSampler>;
    dd.CreateShaderModule = create<VkShaderModuleCreateInfo, VkShaderModule>;
    dd.DestroyShaderModule = destroy<VkShaderModule>;
    dd.CreateDescriptorSetLayout = create<VkDescriptorSetLayoutCreateInfo, VkDescriptorSetLayout>;
    dd.DestroyDescriptorSetLayout = destroy<VkDescriptorSetLayout>;
    dd.CreatePipelineLayout = create<VkPipelineLayoutCreateInfo, VkPipelineLayout>;
    dd.DestroyPipelineLayout = destroy<VkPipelineLayout>;
    dd.CreateDescriptorPool = create<VkDescriptorPoolCreateInfo, VkDescriptorPool>;
    dd.DestroyDescriptorPool = destroy<VkDescriptorPool>; dd.ResetDescriptorPool = reset_pool;
    dd.AllocateDescriptorSets = allocate_sets; dd.UpdateDescriptorSets = update_sets;
    dd.CreateComputePipelines = create_pipelines; dd.DestroyPipeline = destroy<VkPipeline>;
    dd.CmdBindPipeline = bind_pipeline; dd.CmdBindDescriptorSets = bind_sets; dd.CmdDispatch = dispatch;
    dd.CmdPipelineBarrier = barrier; dd.CmdClearColorImage = clear_image; dd.CmdCopyImage = copy_image;
    ddfg::FrameGen fg;
    assert(fg.init(&dd, &id, reinterpret_cast<VkPhysicalDevice>(1), reinterpret_cast<VkDevice>(2),
                   0, reinterpret_cast<VkQueue>(3)));
    ddfg::Config cfg;
    cfg.enabled = true; cfg.model = model; cfg.perfPreset = preset; cfg.flowScale = 0.6f;
    fg.configure(cfg);
    assert(fg.onResize({width, height}, VK_FORMAT_R8G8B8A8_UNORM));
    assert(mode == "resources" || mode == "record" || mode == "resize" || mode == "preset");
    for (unsigned repeat = 0; repeat < repeats; repeat++) {
        const auto old_buffers = buffer_creates, old_sets = set_creates;
        if (mode != "record") check_images(width, height, fg.flowFinest());
        record(fg, model, width, height, generations);
        if (mode != "record") {
            assert(buffer_creates - old_buffers == static_cast<unsigned>(15 - fg.flowFinest()));
            assert(set_creates - old_sets == static_cast<unsigned>(54 - 4 * fg.flowFinest()));
        }
        if (repeat + 1 == repeats) break;
        if (mode == "resize") {
            width += 17; height += 9;
            assert(fg.onResize({width, height}, VK_FORMAT_R8G8B8A8_UNORM));
        } else if (mode == "preset") {
            cfg.perfPreset = (cfg.perfPreset + 1) % 3;
            fg.configure(cfg);
        } else {
            assert(mode == "record");
        }
    }
    uint64_t bytes = 0;
    for (const auto &[image, info] : images) {
        (void)image;
        bytes += uint64_t(info.extent.width) * info.extent.height *
            (info.format == VK_FORMAT_R32_SFLOAT ? 4 : 8);
    }
    std::printf("PASS images=%zu bytes=%llu buffers=%u sets=%u dispatches=%zu trace=%016llx\n",
                images.size(), static_cast<unsigned long long>(bytes), buffer_creates, set_creates,
                dispatches.size(), static_cast<unsigned long long>(command_hash));
    fg.destroy();
    assert(images.empty() && views.empty());
}
