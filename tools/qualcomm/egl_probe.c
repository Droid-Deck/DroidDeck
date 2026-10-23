#include <EGL/egl.h>
#include <GL/gl.h>
#include <stdio.h>
#include <string.h>

int main(void)
{
    EGLDisplay display = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    EGLint major, minor, count;
    const EGLint attributes[] = { EGL_SURFACE_TYPE, EGL_PBUFFER_BIT,
        EGL_RENDERABLE_TYPE, EGL_OPENGL_BIT, EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8,
        EGL_BLUE_SIZE, 8, EGL_ALPHA_SIZE, 8, EGL_NONE };
    const EGLint size[] = { EGL_WIDTH, 16, EGL_HEIGHT, 16, EGL_NONE };
    EGLConfig config;
    if (!eglInitialize(display, &major, &minor) || !eglBindAPI(EGL_OPENGL_API) ||
        !eglChooseConfig(display, attributes, &config, 1, &count) || count != 1) {
        fprintf(stderr, "EGL setup failed: 0x%x\n", eglGetError());
        return 1;
    }
    EGLSurface surface = eglCreatePbufferSurface(display, config, size);
    EGLContext context = eglCreateContext(display, config, EGL_NO_CONTEXT, NULL);
    if (surface == EGL_NO_SURFACE || context == EGL_NO_CONTEXT ||
        !eglMakeCurrent(display, surface, surface, context)) {
        fprintf(stderr, "EGL context failed: 0x%x\n", eglGetError());
        return 1;
    }
    const char *renderer = (const char *)glGetString(GL_RENDERER);
    printf("Renderer: %s\nOpenGL: %s\n", renderer, glGetString(GL_VERSION));
    if (!renderer || !strstr(renderer, "zink") || !strstr(renderer, "Adreno") ||
        strstr(renderer, "llvmpipe")) return 1;
    glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
    glClear(GL_COLOR_BUFFER_BIT);
    glViewport(0, 0, 16, 16);
    glColor4f(1.0f, 0.0f, 0.0f, 1.0f);
    glBegin(GL_TRIANGLES);
    glVertex2f(-1.0f, -1.0f);
    glVertex2f(3.0f, -1.0f);
    glVertex2f(-1.0f, 3.0f);
    glEnd();
    unsigned char pixels[16 * 16 * 4];
    memset(pixels, 0, sizeof(pixels));
    glReadPixels(0, 0, 16, 16, GL_RGBA, GL_UNSIGNED_BYTE, pixels);
    if (glGetError() != GL_NO_ERROR) return 1;
    for (unsigned i = 0; i < sizeof(pixels); i += 4) {
        if (pixels[i] != 255 || pixels[i + 1] || pixels[i + 2] || pixels[i + 3] != 255) {
            fprintf(stderr, "Readback mismatch at pixel %u\n", i / 4);
            return 1;
        }
    }
    puts("GPU triangle/readback: all 256 pixels passed");
    eglMakeCurrent(display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
    eglDestroyContext(display, context);
    eglDestroySurface(display, surface);
    eglTerminate(display);
    return 0;
}
