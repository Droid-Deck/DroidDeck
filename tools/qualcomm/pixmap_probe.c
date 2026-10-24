#include <GL/gl.h>
#include <GL/glx.h>
#include <GL/glxext.h>
#include <X11/Xlib.h>
#include <stdio.h>
#include <string.h>

int main(void)
{
    XInitThreads();
    Display *display = XOpenDisplay(NULL);
    int opcode, event, error;
    if (!display || XQueryExtension(display, "DRI3", &opcode, &event, &error)) {
        fprintf(stderr, "This probe requires an X11 server without DRI3\n");
        return 1;
    }
    const int attributes[] = {
        GLX_DRAWABLE_TYPE, GLX_PIXMAP_BIT | GLX_WINDOW_BIT,
        GLX_RENDER_TYPE, GLX_RGBA_BIT,
        GLX_BIND_TO_TEXTURE_RGB_EXT, True,
        GLX_RED_SIZE, 8, GLX_GREEN_SIZE, 8, GLX_BLUE_SIZE, 8, None
    };
    int count;
    GLXFBConfig *configs = glXChooseFBConfig(display, DefaultScreen(display), attributes, &count);
    XVisualInfo *visual = NULL;
    GLXFBConfig config = NULL;
    for (int i = 0; configs && i < count; ++i) {
        visual = glXGetVisualFromFBConfig(display, configs[i]);
        if (visual && visual->depth == 24) {
            config = configs[i];
            break;
        }
        if (visual) XFree(visual);
        visual = NULL;
    }
    if (!config) return 1;
    const int width = 17, height = 9;
    Colormap colormap = XCreateColormap(display, RootWindow(display, visual->screen), visual->visual, AllocNone);
    XSetWindowAttributes window_attributes = { .colormap = colormap };
    Window output = XCreateWindow(display, RootWindow(display, visual->screen), 0, 0, width, height,
                                 0, visual->depth, InputOutput, visual->visual, CWColormap, &window_attributes);
    XMapWindow(display, output);
    XSync(display, False);
    GLXContext context = glXCreateNewContext(display, config, GLX_RGBA_TYPE, NULL, True);
    if (!context || !glXMakeContextCurrent(display, output, output, context)) return 1;
    const char *renderer = (const char *)glGetString(GL_RENDERER);
    printf("Renderer: %s\n", renderer ? renderer : "unknown");
    if (!renderer || !strstr(renderer, "zink") || !strstr(renderer, "Adreno")) return 1;
    Pixmap pixmap = XCreatePixmap(display, RootWindow(display, visual->screen), width, height, visual->depth);
    GC gc = XCreateGC(display, pixmap, 0, NULL);
    const int texture_attributes[] = {
        GLX_TEXTURE_TARGET_EXT, GLX_TEXTURE_2D_EXT,
        GLX_TEXTURE_FORMAT_EXT, GLX_TEXTURE_FORMAT_RGB_EXT, None
    };
    GLXPixmap texture_pixmap = glXCreatePixmap(display, config, pixmap, texture_attributes);
    PFNGLXBINDTEXIMAGEEXTPROC bind = (PFNGLXBINDTEXIMAGEEXTPROC)glXGetProcAddressARB((const GLubyte *)"glXBindTexImageEXT");
    PFNGLXRELEASETEXIMAGEEXTPROC release = (PFNGLXRELEASETEXIMAGEEXTPROC)glXGetProcAddressARB((const GLubyte *)"glXReleaseTexImageEXT");
    if (!texture_pixmap || !bind || !release) {
        fprintf(stderr, "GLX pixmap setup failed\n");
        return 1;
    }
    GLuint texture;
    glGenTextures(1, &texture);
    glBindTexture(GL_TEXTURE_2D, texture);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
    glEnable(GL_TEXTURE_2D);
    glDisable(GL_DITHER);
    glViewport(0, 0, width, height);
    for (int phase = 0; phase < 2; ++phase) {
        XSetForeground(display, gc, phase ? visual->blue_mask : visual->red_mask);
        XFillRectangle(display, pixmap, gc, 0, 0, 8, height);
        XSetForeground(display, gc, phase ? visual->red_mask | visual->green_mask : visual->green_mask);
        XFillRectangle(display, pixmap, gc, 8, 0, width - 8, height);
        XSync(display, False);
        bind(display, texture_pixmap, GLX_FRONT_LEFT_EXT, NULL);
        glBegin(GL_QUADS);
        glTexCoord2f(0, 0); glVertex2f(-1, -1);
        glTexCoord2f(1, 0); glVertex2f(1, -1);
        glTexCoord2f(1, 1); glVertex2f(1, 1);
        glTexCoord2f(0, 1); glVertex2f(-1, 1);
        glEnd();
        unsigned char pixels[17 * 9 * 4];
        glReadPixels(0, 0, width, height, GL_RGBA, GL_UNSIGNED_BYTE, pixels);
        if (glGetError() != GL_NO_ERROR) return 1;
        for (int y = 0; y < height; ++y) {
            for (int x = 0; x < width; ++x) {
                unsigned char *pixel = &pixels[(y * width + x) * 4];
                int red = phase ? x >= 8 : x < 8;
                int green = x >= 8;
                int blue = phase && x < 8;
                if (pixel[0] != red * 255 || pixel[1] != green * 255 || pixel[2] != blue * 255 || pixel[3] != 255) {
                    fprintf(stderr, "Phase %d pixel %d,%d: %u %u %u %u\n", phase, x, y, pixel[0], pixel[1], pixel[2], pixel[3]);
                    return 1;
                }
            }
        }
        release(display, texture_pixmap, GLX_FRONT_LEFT_EXT);
    }
    puts("Pixmap bind/update: all 306 GPU readback pixels passed without DRI3");
    glXDestroyPixmap(display, texture_pixmap);
    XFreeGC(display, gc);
    XFreePixmap(display, pixmap);
    glDeleteTextures(1, &texture);
    glXMakeContextCurrent(display, None, None, NULL);
    glXDestroyContext(display, context);
    XDestroyWindow(display, output);
    XFreeColormap(display, colormap);
    XFree(visual);
    XFree(configs);
    XCloseDisplay(display);
    return 0;
}
