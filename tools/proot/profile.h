/* SPDX-License-Identifier: GPL-2.0-or-later */
#ifndef DROIDDECK_PROOT_PROFILE_H
#define DROIDDECK_PROOT_PROFILE_H

#include <errno.h>
#include <inttypes.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>

struct dd_profile_counter {
	uint64_t calls, wall_ns, cpu_ns, max_ns;
};
static struct dd_profile_counter dd_profile_counts[PR_NB_SYSNUM][2];
static int dd_profile_on = -1;
static uint64_t dd_profile_id, dd_profile_next, dd_profile_slow_ns = 1000000;
static unsigned dd_profile_samples;

static uint64_t dd_profile_clock(clockid_t clock)
{
	struct timespec ts;
	if (clock_gettime(clock, &ts) != 0) return 0;
	return (uint64_t)ts.tv_sec * 1000000000 + ts.tv_nsec;
}

static void dd_profile_flush(void)
{
	int saved = errno;
	for (unsigned nr = 0; nr < PR_NB_SYSNUM; nr++) {
		for (unsigned stage = 0; stage < 2; stage++) {
			const struct dd_profile_counter *c = &dd_profile_counts[nr][stage];
			if (!c->calls) continue;
			fprintf(stderr, "proot-profile {\"type\":\"total\",\"tracer\":%d,\"id\":%" PRIu64
				",\"syscall\":\"%s\",\"stage\":\"%s\",\"calls\":%" PRIu64
				",\"wall_ns\":%" PRIu64 ",\"cpu_ns\":%" PRIu64 ",\"max_ns\":%" PRIu64 "}\n",
				getpid(), dd_profile_id, stringify_sysnum(nr), stage ? "exit" : "enter",
				c->calls, c->wall_ns, c->cpu_ns, c->max_ns);
		}
	}
	errno = saved;
}

static int dd_profile_enabled(void)
{
	if (dd_profile_on >= 0) return dd_profile_on;
	int saved = errno;
	const char *value = getenv("PROOT_PROFILE");
	dd_profile_on = value && strcmp(value, "1") == 0;
	if (dd_profile_on) {
		value = getenv("PROOT_PROFILE_SLOW_US");
		if (value && *value) {
			char *end;
			unsigned long long us = strtoull(value, &end, 10);
			if (!*end && us > 0 && us <= 1000000000ULL) dd_profile_slow_ns = us * 1000;
		}
		dd_profile_id = dd_profile_clock(CLOCK_MONOTONIC);
		dd_profile_next = dd_profile_id + 5000000000ULL;
		atexit(dd_profile_flush);
		fprintf(stderr, "proot-profile {\"type\":\"start\",\"tracer\":%d,\"id\":%" PRIu64
			",\"slow_ns\":%" PRIu64 ",\"sample_limit\":32,\"sample_window_ms\":5000,\"scope\":\"syscall-handler\"}\n",
			getpid(), dd_profile_id, dd_profile_slow_ns);
	}
	errno = saved;
	return dd_profile_on;
}

static void dd_profile_record(unsigned nr, int entering, pid_t pid,
		uint64_t start_wall, uint64_t start_cpu)
{
	int saved = errno;
	uint64_t cpu = dd_profile_clock(CLOCK_THREAD_CPUTIME_ID);
	uint64_t wall = dd_profile_clock(CLOCK_MONOTONIC);
	if (nr >= PR_NB_SYSNUM) nr = PR_void;
	if (!start_wall || !start_cpu || wall < start_wall || cpu < start_cpu) goto done;
	uint64_t elapsed = wall - start_wall, used = cpu - start_cpu;
	struct dd_profile_counter *c = &dd_profile_counts[nr][!entering];
	c->calls++;
	c->wall_ns += elapsed;
	c->cpu_ns += used;
	if (elapsed > c->max_ns) c->max_ns = elapsed;
	if (wall >= dd_profile_next) {
		dd_profile_flush();
		dd_profile_next = wall + 5000000000ULL;
		dd_profile_samples = 0;
	}
	if (elapsed >= dd_profile_slow_ns && dd_profile_samples < 32) {
		dd_profile_samples++;
		fprintf(stderr, "proot-profile {\"type\":\"slow\",\"tracer\":%d,\"id\":%" PRIu64
			",\"pid\":%d,\"syscall\":\"%s\",\"stage\":\"%s\",\"wall_ns\":%" PRIu64
			",\"cpu_ns\":%" PRIu64 "}\n", getpid(), dd_profile_id, pid,
			stringify_sysnum(nr), entering ? "enter" : "exit", elapsed, used);
	}
 done:
	errno = saved;
}
#endif
