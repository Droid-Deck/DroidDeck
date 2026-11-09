#include <assert.h>
#include <stddef.h>
#include <stdint.h>

#include "../../../app/src/main/cpp/waylandcomp/src/surface_format.h"

int main(void) {
    const uint32_t concrete[] = {44, 37};
    const uint32_t undefined[] = {0};
    const uint32_t mixed[] = {0, 44};
    assert(droiddeck_select_surface_format(concrete, 2, 44) == 44);
    assert(droiddeck_select_surface_format(undefined, 1, 44) == 44);
    assert(droiddeck_select_surface_format(mixed, 2, 44) == 44);
    assert(droiddeck_select_surface_format(NULL, 0, 44) == 44);
    return 0;
}
