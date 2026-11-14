/*
 * droiddeck-glprobe: does OpenGL through GLX start on this session's display, the way the Steam
 * client's first window asks for it (glXChooseVisual, then a context)? Run once before the client
 * on the Mali (Venus) route, where Zink failed on a Mali-G615 with nothing but "failed to load
 * driver: zink" and the client gave up ("UpdateUI GL not available"). Exit 0 when a visual and a
 * context come back, 1 when GL does not start, 2 when there is no X display or no libGL.
 * libX11 and libGL are opened at run time, so this builds without their headers.
 */
#include <dlfcn.h>
#include <stdio.h>

typedef void *(*open_display_fn)(const char *);
typedef int (*close_display_fn)(void *);
typedef int (*default_screen_fn)(void *);
typedef void *(*choose_visual_fn)(void *, int, int *);
typedef void *(*create_context_fn)(void *, void *, void *, int);
typedef void (*destroy_context_fn)(void *, void *);
typedef int (*free_fn)(void *);

#define GLX_RGBA 4
#define GLX_DOUBLEBUFFER 5
#define GLX_RED_SIZE 8
#define GLX_GREEN_SIZE 9
#define GLX_BLUE_SIZE 10

int main(void)
{
    void *x11 = dlopen("libX11.so.6", RTLD_NOW | RTLD_GLOBAL);
    void *gl = dlopen("libGL.so.1", RTLD_NOW | RTLD_GLOBAL);
    if (!x11 || !gl) {
        printf("glprobe: cannot open %s: %s\n", x11 ? "libGL.so.1" : "libX11.so.6", dlerror());
        return 2;
    }
    open_display_fn open_display = (open_display_fn)dlsym(x11, "XOpenDisplay");
    close_display_fn close_display = (close_display_fn)dlsym(x11, "XCloseDisplay");
    default_screen_fn default_screen = (default_screen_fn)dlsym(x11, "XDefaultScreen");
    free_fn x_free = (free_fn)dlsym(x11, "XFree");
    choose_visual_fn choose_visual = (choose_visual_fn)dlsym(gl, "glXChooseVisual");
    create_context_fn create_context = (create_context_fn)dlsym(gl, "glXCreateContext");
    destroy_context_fn destroy_context = (destroy_context_fn)dlsym(gl, "glXDestroyContext");
    if (!open_display || !close_display || !default_screen || !x_free || !choose_visual || !create_context ||
        !destroy_context) {
        printf("glprobe: missing X11/GLX entry points\n");
        return 2;
    }
    void *dpy = open_display(NULL);
    if (!dpy) {
        printf("glprobe: no X display\n");
        return 2;
    }
    int attrs[] = {GLX_RGBA, GLX_DOUBLEBUFFER, GLX_RED_SIZE, 8, GLX_GREEN_SIZE, 8, GLX_BLUE_SIZE, 8, 0};
    void *visual = choose_visual(dpy, default_screen(dpy), attrs);
    if (!visual) {
        printf("glprobe: FAIL glXChooseVisual\n");
        close_display(dpy);
        return 1;
    }
    void *ctx = create_context(dpy, visual, NULL, 1);
    x_free(visual);
    if (!ctx) {
        printf("glprobe: FAIL glXCreateContext\n");
        close_display(dpy);
        return 1;
    }
    destroy_context(dpy, ctx);
    close_display(dpy);
    printf("glprobe: OK (visual and context)\n");
    return 0;
}
