#ifndef DROIDDECK_CLOCK_DOMAIN_H
#define DROIDDECK_CLOCK_DOMAIN_H

#include <stdint.h>

void droiddeck_clock_domain_set_control(const char *path);
int64_t droiddeck_clock_domain_now_ns(void);
uint32_t droiddeck_clock_domain_now_ms(void);
int64_t droiddeck_clock_domain_adjust_ns(int64_t value);

#endif
