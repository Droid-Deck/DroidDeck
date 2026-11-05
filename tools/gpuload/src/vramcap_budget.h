#ifndef VRAMCAP_BUDGET_H
#define VRAMCAP_BUDGET_H

#include <stdint.h>
#include <vulkan/vulkan.h>

static inline uint64_t vramcap_parse_mb(const char *text) {
    if (!text || !*text) return 0;
    uint64_t value = 0, limit = UINT64_MAX >> 20;
    for (; *text; text++) {
        if (*text < '0' || *text > '9') return 0;
        unsigned digit = (unsigned)(*text - '0');
        if (value > (limit - digit) / 10) return 0;
        value = value * 10 + digit;
    }
    return value << 20;
}

static inline VkDeviceSize vramcap_clamp(VkDeviceSize value, uint64_t cap) {
    return cap && value > cap ? cap : value;
}

static inline void vramcap_properties(VkPhysicalDeviceMemoryProperties *p, uint64_t cap) {
    for (uint32_t i = 0; i < p->memoryHeapCount && i < VK_MAX_MEMORY_HEAPS; i++)
        if (p->memoryHeaps[i].flags & VK_MEMORY_HEAP_DEVICE_LOCAL_BIT)
            p->memoryHeaps[i].size = vramcap_clamp(p->memoryHeaps[i].size, cap);
}

static inline void vramcap_budget(const VkPhysicalDeviceMemoryProperties *p,
                                 VkPhysicalDeviceMemoryBudgetPropertiesEXT *budget, uint64_t cap) {
    for (uint32_t i = 0; i < p->memoryHeapCount && i < VK_MAX_MEMORY_HEAPS; i++)
        if (p->memoryHeaps[i].flags & VK_MEMORY_HEAP_DEVICE_LOCAL_BIT) {
            budget->heapBudget[i] = vramcap_clamp(budget->heapBudget[i], cap);
            budget->heapBudget[i] = vramcap_clamp(budget->heapBudget[i], p->memoryHeaps[i].size);
        }
}

#endif
