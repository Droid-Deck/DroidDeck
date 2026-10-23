/* SPDX-License-Identifier: Apache-2.0 */
#define VK_NO_PROTOTYPES
#include "spirv_names.h"
#include <assert.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static const VkAllocationCallbacks allocator = {0};
static unsigned allocations, outstanding, fail_allocation;

void *hybris_scaled_alloc(const VkAllocationCallbacks *a, size_t size,
    VkSystemAllocationScope scope)
{
    assert(a == &allocator && scope == VK_SYSTEM_ALLOCATION_SCOPE_COMMAND);
    if (++allocations == fail_allocation) return NULL;
    void *p = malloc(size);
    assert(p);
    ++outstanding;
    return p;
}

void hybris_scaled_free(const VkAllocationCallbacks *a, void *p)
{
    assert(a == &allocator);
    if (p) { assert(outstanding); --outstanding; free(p); }
}

#define HEADER 0x07230203, 0x00010000, 0, 8, 0
#define CAPABILITY_SHADER (2u << 16 | 17), 1
#define NAME_UV (3u << 16 | 5), 1, 0x00007675               /* OpName %1 "uv" */
#define MEMBER_NAME_A (4u << 16 | 6), 2, 0, 0x00000061      /* OpMemberName %2 0 "a" */
#define LOCATION_1 (4u << 16 | 71), 1, 30, 1                /* OpDecorate %1 Location 1 */

static void strip(const uint32_t *code, size_t size, VkResult want, uint32_t **out, size_t *out_size, unsigned *removed)
{
    VkResult result = hybris_spirv_strip_names(code, size, &allocator, out, out_size, removed);
    assert(result == want);
}

int main(void)
{
    printf("enabled=%d\n", hybris_spirv_strip_names_enabled());
    uint32_t *out;
    size_t out_size;
    unsigned removed;

    /* Both kinds of name go; every other instruction stays, in order. */
    const uint32_t named[] = {HEADER, CAPABILITY_SHADER, NAME_UV, MEMBER_NAME_A, LOCATION_1};
    const uint32_t unnamed[] = {HEADER, CAPABILITY_SHADER, LOCATION_1};
    strip(named, sizeof(named), VK_SUCCESS, &out, &out_size, &removed);
    assert(out && removed == 2 && out_size == sizeof(unnamed));
    assert(!memcmp(out, unnamed, sizeof(unnamed)));
    hybris_scaled_free(&allocator, out);

    /* Nothing to drop, or code this pass does not parse: the original is used unchanged. */
    strip(unnamed, sizeof(unnamed), VK_SUCCESS, &out, &out_size, &removed);
    assert(!out && !removed && !out_size);
    const uint32_t zero_count[] = {HEADER, NAME_UV, 0};
    strip(zero_count, sizeof(zero_count), VK_SUCCESS, &out, &out_size, &removed);
    assert(!out);
    const uint32_t overrun[] = {HEADER, (9u << 16 | 5), 1};
    strip(overrun, sizeof(overrun), VK_SUCCESS, &out, &out_size, &removed);
    assert(!out);
    uint32_t swapped[] = {HEADER, NAME_UV};
    swapped[0] = 0x03022307;
    strip(swapped, sizeof(swapped), VK_SUCCESS, &out, &out_size, &removed);
    assert(!out);
    strip(named, sizeof(named) - 2, VK_SUCCESS, &out, &out_size, &removed);
    assert(!out);
    strip(NULL, 0, VK_SUCCESS, &out, &out_size, &removed);
    assert(!out);

    /* An allocation failure is reported and leaves nothing behind. */
    fail_allocation = allocations + 1;
    strip(named, sizeof(named), VK_ERROR_OUT_OF_HOST_MEMORY, &out, &out_size, &removed);
    assert(!out);
    assert(!outstanding);
    puts("hybris strip names ok");
    return 0;
}
