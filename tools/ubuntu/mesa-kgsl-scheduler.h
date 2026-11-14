#ifndef DROIDDECK_KGSL_SCHEDULER_H
#define DROIDDECK_KGSL_SCHEDULER_H
#include <pthread.h>
#include <time.h>
#include <unistd.h>

struct droiddeck_kgsl_queue {
   droiddeck_kgsl_queue *next;
   int fd, priority;
   uint32_t context, submitted, retired;
   unsigned waiting;
   bool has_submission, disabled;
};

static pthread_mutex_t dd_kgsl_lock = PTHREAD_MUTEX_INITIALIZER;
static pthread_cond_t dd_kgsl_cond;
static pthread_once_t dd_kgsl_once = PTHREAD_ONCE_INIT;
static droiddeck_kgsl_queue *dd_kgsl_queues;
static bool dd_kgsl_worker_started, dd_kgsl_draining, dd_kgsl_admitting;
static unsigned dd_kgsl_submitting;
static uint64_t dd_kgsl_yield_until;

static uint64_t
dd_kgsl_now()
{
   struct timespec value;
   clock_gettime(CLOCK_MONOTONIC, &value);
   return uint64_t(value.tv_sec) * 1000000000ULL + value.tv_nsec;
}

static void
dd_kgsl_initialize()
{
   pthread_condattr_t attribute;
   pthread_condattr_init(&attribute);
   pthread_condattr_setclock(&attribute, CLOCK_MONOTONIC);
   pthread_cond_init(&dd_kgsl_cond, &attribute);
   pthread_condattr_destroy(&attribute);
}

static int
dd_kgsl_priority(int fd, int priority)
{
   // Only new empty contexts change priority; running queues never migrate.
   struct request_type {
      int32_t pid, tid, priority;
   } request = {getpid(), 0, priority};
   return safe_ioctl(fd, _IOWR(KGSL_IOC_TYPE, 0xb0, request_type), &request);
}

static bool
dd_kgsl_pending(const droiddeck_kgsl_queue *queue)
{
   return queue->has_submission && !queue->disabled &&
      int32_t(queue->submitted - queue->retired) > 0;
}

static void *
dd_kgsl_worker(void *)
{
   const uint64_t slice = 8000000ULL, period = 2 * slice;
   uint64_t queries = 0, batches = 0, last_report = dd_kgsl_now();
   pthread_mutex_lock(&dd_kgsl_lock);
   while (true) {
      while (!dd_kgsl_queues)
         pthread_cond_wait(&dd_kgsl_cond, &dd_kgsl_lock);
      bool pending = false, waiting = false;
      for (auto queue = dd_kgsl_queues; queue; queue = queue->next) {
         if (dd_kgsl_pending(queue)) {
            struct kgsl_cmdstream_readtimestamp_ctxtid request = {
               .context_id = queue->context,
               .type = KGSL_TIMESTAMP_RETIRED,
            };
            ++queries;
            if (safe_ioctl(queue->fd, IOCTL_KGSL_CMDSTREAM_READTIMESTAMP_CTXTID, &request) == 0)
               queue->retired = request.timestamp;
            else
               queue->disabled = true;
         }
         pending |= dd_kgsl_pending(queue);
         waiting |= queue->waiting != 0 && !queue->disabled;
      }
      const uint64_t now = dd_kgsl_now();
      const bool boost_phase = now % period < slice;
      if (dd_kgsl_admitting &&
          (!boost_phase || (!waiting && !pending && !dd_kgsl_submitting))) {
         dd_kgsl_admitting = false;
         dd_kgsl_draining = true;
      }
      if (!pending && !dd_kgsl_submitting) {
         if (dd_kgsl_draining) {
            dd_kgsl_yield_until = now + slice;
            dd_kgsl_draining = false;
         }
         if (!dd_kgsl_admitting && waiting && boost_phase && now >= dd_kgsl_yield_until) {
            dd_kgsl_admitting = true;
            ++batches;
         }
      }
      pthread_cond_broadcast(&dd_kgsl_cond);
      const uint64_t after = dd_kgsl_now();
      if (after - last_report >= 10000000000ULL) {
         struct timespec cpu;
         clock_gettime(CLOCK_THREAD_CPUTIME_ID, &cpu);
         fprintf(stderr, "DroidDeck KGSL submission batches pid=%d queries=%llu batches=%llu cpu_ns=%llu\n",
            getpid(), (unsigned long long)queries, (unsigned long long)batches,
            (unsigned long long)(uint64_t(cpu.tv_sec) * 1000000000ULL + cpu.tv_nsec));
         last_report = after;
      }
      if (pending || waiting || dd_kgsl_admitting || dd_kgsl_submitting) {
         const uint64_t deadline_ns = after + 1000000ULL;
         struct timespec deadline = {
            .tv_sec = time_t(deadline_ns / 1000000000ULL),
            .tv_nsec = long(deadline_ns % 1000000000ULL),
         };
         pthread_cond_timedwait(&dd_kgsl_cond, &dd_kgsl_lock, &deadline);
      } else {
         pthread_cond_wait(&dd_kgsl_cond, &dd_kgsl_lock);
      }
   }
}

static droiddeck_kgsl_queue *
dd_kgsl_register(int fd, uint32_t context)
{
   const char *enabled = getenv("DROIDDECK_KGSL_SLICES");
   if (!enabled || strcmp(enabled, "1")) return nullptr;
   auto queue = static_cast<droiddeck_kgsl_queue *>(calloc(1, sizeof(droiddeck_kgsl_queue)));
   if (!queue) return nullptr;
   queue->fd = os_dupfd_cloexec(fd);
   queue->context = context;
   queue->priority = 8;
   if (queue->fd < 0) {
      free(queue);
      return nullptr;
   }
   pthread_once(&dd_kgsl_once, dd_kgsl_initialize);
   pthread_mutex_lock(&dd_kgsl_lock);
   if (dd_kgsl_priority(queue->fd, 7) != 0) {
      pthread_mutex_unlock(&dd_kgsl_lock);
      close(queue->fd);
      free(queue);
      return nullptr;
   }
   if (!dd_kgsl_queues) {
      dd_kgsl_draining = false;
      dd_kgsl_admitting = false;
      dd_kgsl_yield_until = 0;
   }
   queue->next = dd_kgsl_queues;
   dd_kgsl_queues = queue;
   if (!dd_kgsl_worker_started) {
      pthread_t thread;
      pthread_attr_t attribute;
      pthread_attr_init(&attribute);
      pthread_attr_setstacksize(&attribute, 128 * 1024);
      const int result = pthread_create(&thread, &attribute, dd_kgsl_worker, nullptr);
      pthread_attr_destroy(&attribute);
      if (result != 0) {
         dd_kgsl_queues = queue->next;
         pthread_mutex_unlock(&dd_kgsl_lock);
         close(queue->fd);
         free(queue);
         return nullptr;
      }
      pthread_detach(thread);
      dd_kgsl_worker_started = true;
   }
   pthread_cond_signal(&dd_kgsl_cond);
   pthread_mutex_unlock(&dd_kgsl_lock);
   fprintf(stderr, "DroidDeck KGSL static batch priority=7 context=%u pid=%d\n",
      context, getpid());
   return queue;
}

static void
dd_kgsl_begin_submit(droiddeck_kgsl_queue *queue)
{
   if (!queue) return;
   pthread_mutex_lock(&dd_kgsl_lock);
   ++queue->waiting;
   pthread_cond_signal(&dd_kgsl_cond);
   while (!queue->disabled &&
          (!dd_kgsl_admitting || dd_kgsl_draining ||
           (dd_kgsl_pending(queue) && uint32_t(queue->submitted - queue->retired) >= 2) ||
           dd_kgsl_now() % 16000000ULL >= 8000000ULL))
      pthread_cond_wait(&dd_kgsl_cond, &dd_kgsl_lock);
   --queue->waiting;
   ++dd_kgsl_submitting;
   pthread_mutex_unlock(&dd_kgsl_lock);
}

static void
dd_kgsl_end_submit(droiddeck_kgsl_queue *queue, uint32_t timestamp, bool submitted)
{
   if (!queue) return;
   pthread_mutex_lock(&dd_kgsl_lock);
   if (submitted) {
      queue->submitted = timestamp;
      queue->has_submission = true;
   }
   --dd_kgsl_submitting;
   pthread_cond_signal(&dd_kgsl_cond);
   pthread_mutex_unlock(&dd_kgsl_lock);
}

static void
dd_kgsl_unregister(droiddeck_kgsl_queue *queue)
{
   if (!queue) return;
   pthread_mutex_lock(&dd_kgsl_lock);
   auto link = &dd_kgsl_queues;
   while (*link && *link != queue) link = &(*link)->next;
   if (*link) *link = queue->next;
   pthread_cond_signal(&dd_kgsl_cond);
   pthread_mutex_unlock(&dd_kgsl_lock);
   close(queue->fd);
   free(queue);
}
#endif
