#include <assert.h>
#include <stdarg.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>

#define WL_DISPLAY_ERROR 0
enum wl_protocol_logger_type { WL_PROTOCOL_LOGGER_REQUEST, WL_PROTOCOL_LOGGER_EVENT };
struct wl_message { const char *name, *signature; const void **types; };
struct wl_interface { const char *name; int version, method_count; const struct wl_message *methods;
                      int event_count; const struct wl_message *events; };
union wl_argument { int32_t i; uint32_t u; const char *s; void *o; };
struct wl_client { const char *name; };
struct wl_resource { const char *cls; uint32_t id; struct wl_client *client; };
struct wl_protocol_logger_message {
    struct wl_resource *resource;
    int message_opcode;
    const struct wl_message *message;
    int arguments_count;
    const union wl_argument *arguments;
};

static const struct wl_message display_events[] = {{"error", "ous", NULL}, {"delete_id", "u", NULL}};
static const struct wl_interface wl_display_interface = {"wl_display", 1, 0, NULL, 2, display_events};
static const struct wl_message buffer_events[] = {{"release", "", NULL}};

static char g_logged[1024];
static int g_lines;
static void droiddeck_log(const char *tag, const char *fmt, ...) {
    va_list ap;
    int n = snprintf(g_logged, sizeof(g_logged), "[%s] ", tag);
    va_start(ap, fmt);
    vsnprintf(g_logged + n, sizeof(g_logged) - (size_t)n, fmt, ap);
    va_end(ap);
    g_lines++;
}
static const char *client_name(struct wl_client *client) { return client->name; }
static struct wl_client *wl_resource_get_client(struct wl_resource *r) { return r->client; }
static const char *wl_resource_get_class(struct wl_resource *r) { return r->cls; }
static uint32_t wl_resource_get_id(struct wl_resource *r) { return r->id; }

#include "log_protocol_error.inc"

int main(int argc, char **argv) {
    assert(argc == 2);
    struct wl_client gamescope = {"gamescope"};
    struct wl_resource display = {"wl_display", 1, &gamescope};
    struct wl_resource handle = {"android_wlegl", 12, &gamescope};
    struct wl_resource buffer = {"wl_buffer", 40, &gamescope};
    union wl_argument error[3] = {{.o = &handle}, {.u = 2}, {.s = "Android buffer allocation failed"}};
    struct wl_protocol_logger_message sent = {&display, WL_DISPLAY_ERROR, &display_events[0], 3, error};

    if (!strcmp(argv[1], "error")) {
        log_protocol_error(NULL, WL_PROTOCOL_LOGGER_EVENT, &sent);
        assert(g_lines == 1);
        assert(!strcmp(g_logged, "[program] protocol error sent to gamescope: "
                                 "android_wlegl@12 error 2: Android buffer allocation failed"));
    } else if (!strcmp(argv[1], "anonymous")) {
        union wl_argument bare[3] = {{.o = NULL}, {.u = 1}, {.s = NULL}};
        sent.arguments = bare;
        log_protocol_error(NULL, WL_PROTOCOL_LOGGER_EVENT, &sent);
        assert(g_lines == 1);
        assert(!strcmp(g_logged, "[program] protocol error sent to gamescope: ?@0 error 1: "));
    } else if (!strcmp(argv[1], "quiet")) {
        log_protocol_error(NULL, WL_PROTOCOL_LOGGER_REQUEST, &sent);
        struct wl_protocol_logger_message deleted = {&display, 1, &display_events[1], 1, error};
        log_protocol_error(NULL, WL_PROTOCOL_LOGGER_EVENT, &deleted);
        struct wl_protocol_logger_message release = {&buffer, 0, &buffer_events[0], 0, NULL};
        log_protocol_error(NULL, WL_PROTOCOL_LOGGER_EVENT, &release);
        assert(g_lines == 0);
    } else {
        return 2;
    }
    puts("ok");
    return 0;
}
