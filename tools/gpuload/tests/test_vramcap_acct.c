#include <assert.h>
#include <stdint.h>

#include "vramcap_acct.h"

int main(void) {
    assert(vramcap_clamp(8000, 4000) == 4000);
    assert(vramcap_clamp(3000, 4000) == 3000);
    assert(vramcap_clamp(8000, 0) == 8000);

    uint64_t used = 0;
    assert(vramcap_reserve(&used, 100, 60));
    assert(!vramcap_reserve(&used, 100, 41));
    assert(used == 60);
    assert(vramcap_reserve(&used, 100, 40));
    assert(!vramcap_reserve(&used, 100, 1));
    vramcap_release(&used, 60);
    assert(used == 40);
    vramcap_release(&used, 500);
    assert(used == 0);
    assert(!vramcap_reserve(&used, 100, UINT64_MAX));
    assert(vramcap_reserve(&used, 0, UINT64_MAX / 2));
    return 0;
}
