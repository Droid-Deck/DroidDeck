#include "xr_input_v2.h"

int main(void) {
    DroidDeckXrInputV2 input = droiddeck_xr_input_v2_neutral(7, 3);
    return input.session_generation == 7 && input.origin_epoch == 3 &&
        input.flags == 0 && input.hands[0].source == DROIDDECK_XR_SOURCE_UNKNOWN ? 0 : 1;
}
