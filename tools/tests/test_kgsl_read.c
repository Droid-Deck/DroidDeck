#include <assert.h>
#include <errno.h>
#include <stdio.h>

#include "../quest/kgsl_compat.h"

void bl_kgsl_track(int (*real)(int, unsigned long, void *), int fd, unsigned long request,
                   void *arg, int *rc);

static int read_error, wait_error, waits;
static unsigned int retired;

static int mock(int fd, unsigned long request, void *arg) {
  assert(fd == 42);
  if (request == IOCTL_KGSL_CMDSTREAM_READTIMESTAMP_CTXTID) {
    if (read_error) {
      errno = read_error;
      return -1;
    }
    ((struct kgsl_cmdstream_readtimestamp_ctxtid *)arg)->timestamp = retired;
    return 0;
  }
  assert(request == IOCTL_KGSL_DEVICE_WAITTIMESTAMP_CTXTID);
  struct kgsl_device_waittimestamp_ctxtid *wait = arg;
  assert(wait->context_id == 3 && wait->timeout != 0);
  waits++;
  if (wait_error) {
    errno = wait_error;
    return -1;
  }
  if (wait->timestamp > retired) {
    errno = ETIMEDOUT;
    return -1;
  }
  return 0;
}

static int issue(unsigned long request, void *arg) {
  int rc = mock(42, request, arg);
  bl_kgsl_track(mock, 42, request, arg, &rc);
  return rc;
}

static void submit(unsigned int timestamp) {
  struct kgsl_gpu_command command = {.context_id = 3, .timestamp = timestamp};
  int rc = 0;
  bl_kgsl_track(mock, 42, IOCTL_KGSL_GPU_COMMAND, &command, &rc);
  assert(rc == 0);
}

static int read_timestamp(unsigned int context, unsigned int *timestamp) {
  struct kgsl_cmdstream_readtimestamp_ctxtid read = {
      .context_id = context, .type = KGSL_TIMESTAMP_RETIRED};
  int rc = issue(IOCTL_KGSL_CMDSTREAM_READTIMESTAMP_CTXTID, &read);
  *timestamp = read.timestamp;
  return rc;
}

int main(void) {
  unsigned int timestamp;

  retired = 5;
  assert(read_timestamp(3, &timestamp) == 0 && timestamp == 5 && waits == 0);

  read_error = EACCES;
  retired = 0;
  assert(read_timestamp(3, &timestamp) == 0 && timestamp == 0 && waits == 0);

  submit(1);
  submit(2);
  submit(3);
  assert(read_timestamp(3, &timestamp) == 0 && timestamp == 0 && waits == 1);

  retired = 2;
  waits = 0;
  assert(read_timestamp(3, &timestamp) == 0 && timestamp == 2 && waits == 3);

  waits = 0;
  assert(read_timestamp(3, &timestamp) == 0 && timestamp == 2 && waits == 1);

  retired = 3;
  waits = 0;
  assert(read_timestamp(3, &timestamp) == 0 && timestamp == 3 && waits == 1);

  waits = 0;
  assert(read_timestamp(3, &timestamp) == 0 && timestamp == 3 && waits == 0);

  submit(4);
  retired = 4;
  wait_error = EDEADLK;
  assert(read_timestamp(3, &timestamp) == -1 && errno == EDEADLK);
  wait_error = 0;

  struct kgsl_gpu_aux_command aux = {.context_id = 3, .timestamp = 6};
  int rc = 0;
  bl_kgsl_track(mock, 42, IOCTL_KGSL_GPU_AUX_COMMAND, &aux, &rc);
  retired = 6;
  assert(read_timestamp(3, &timestamp) == 0 && timestamp == 6);

  struct kgsl_drawctxt_create create = {.drawctxt_id = 3};
  bl_kgsl_track(mock, 42, IOCTL_KGSL_DRAWCTXT_CREATE, &create, &rc);
  retired = 0;
  waits = 0;
  assert(read_timestamp(3, &timestamp) == 0 && timestamp == 0 && waits == 0);

  errno = EACCES;
  assert(read_timestamp(5000, &timestamp) == -1 && errno == EACCES);

  puts("PASS: refused timestamp reads answered from retired waits");
}
