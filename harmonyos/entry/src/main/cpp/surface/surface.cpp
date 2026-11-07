#include "surface/surface.h"

#include <EGL/egl.h>
#include <GLES3/gl3.h>
#include <ace/xcomponent/native_interface_xcomponent.h>
#include <native_window/external_window.h>

#include <cstring>
#include <string>
#include <vector>

#include "ddk_log.h"

namespace ddk {

SurfaceHost &SurfaceHost::Instance() {
  static SurfaceHost instance;
  return instance;
}

void SurfaceHost::OnSurfaceCreated(void *window, int32_t width, int32_t height) {
  window_ = window;
  width_ = width;
  height_ = height;
  DDK_LOGI("XComponent surface created: %{public}dx%{public}d", width, height);
  Attach();
}

void SurfaceHost::OnSurfaceChanged(void *window, int32_t width, int32_t height) {
  window_ = window;
  width_ = width;
  height_ = height;
  DDK_LOGI("XComponent surface resized: %{public}dx%{public}d", width, height);
}

void SurfaceHost::OnSurfaceDestroyed() {
  DDK_LOGI("XComponent surface destroyed");
  Detach();
}

bool SurfaceHost::CreateEgl() {
  EGLDisplay display = eglGetDisplay(EGL_DEFAULT_DISPLAY);
  if (display == EGL_NO_DISPLAY || !eglInitialize(display, nullptr, nullptr)) {
    DDK_LOGW("cannot initialise EGL for the desktop surface");
    return false;
  }

  const EGLint configAttribs[] = {
      EGL_RED_SIZE, 8,
      EGL_GREEN_SIZE, 8,
      EGL_BLUE_SIZE, 8,
      EGL_ALPHA_SIZE, 8,
      EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT,
      EGL_NONE
  };
  EGLConfig config = nullptr;
  EGLint configCount = 0;
  if (!eglChooseConfig(display, configAttribs, &config, 1, &configCount) || configCount == 0) {
    DDK_LOGW("no EGL config for the desktop surface");
    eglTerminate(display);
    return false;
  }

  const EGLint contextAttribs[] = {EGL_CONTEXT_CLIENT_VERSION, 3, EGL_NONE};
  EGLContext context = eglCreateContext(display, config, EGL_NO_CONTEXT, contextAttribs);
  if (context == EGL_NO_CONTEXT) {
    DDK_LOGW("cannot create the EGL context for the desktop surface");
    eglTerminate(display);
    return false;
  }

  EGLSurface eglSurface =
      eglCreateWindowSurface(display, config, reinterpret_cast<EGLNativeWindowType>(window_), nullptr);
  if (eglSurface == EGL_NO_SURFACE) {
    DDK_LOGW("cannot create the EGL window surface");
    eglDestroyContext(display, context);
    eglTerminate(display);
    return false;
  }

  if (!eglMakeCurrent(display, eglSurface, eglSurface, context)) {
    DDK_LOGW("cannot make the desktop surface current");
    eglDestroySurface(display, eglSurface);
    eglDestroyContext(display, context);
    eglTerminate(display);
    return false;
  }

  glGenTextures(1, &texture_);
  glBindTexture(GL_TEXTURE_2D, texture_);
  glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
  glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
  glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
  glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);

  display_ = display;
  context_ = context;
  eglSurface_ = eglSurface;
  return true;
}

bool SurfaceHost::Attach() {
  if (window_ == nullptr) {
    DDK_LOGW("no XComponent window to attach to");
    return false;
  }
  if (display_ != nullptr) {
    return true;
  }
  return CreateEgl();
}

void SurfaceHost::Detach() {
  if (display_ != nullptr) {
    eglMakeCurrent(static_cast<EGLDisplay>(display_), EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
    if (eglSurface_ != nullptr) {
      eglDestroySurface(static_cast<EGLDisplay>(display_), static_cast<EGLSurface>(eglSurface_));
    }
    if (context_ != nullptr) {
      eglDestroyContext(static_cast<EGLDisplay>(display_), static_cast<EGLContext>(context_));
    }
    eglTerminate(static_cast<EGLDisplay>(display_));
  }

  display_ = nullptr;
  context_ = nullptr;
  eglSurface_ = nullptr;
  window_ = nullptr;
  receivingFrames_ = false;
  width_ = 0;
  height_ = 0;
}

bool SurfaceHost::IsAttached() const {
  return display_ != nullptr;
}

bool SurfaceHost::IsReceivingFrames() const {
  return receivingFrames_;
}

int32_t SurfaceHost::Width() const {
  return width_;
}

int32_t SurfaceHost::Height() const {
  return height_;
}

bool SurfaceHost::PublishFrame(const uint8_t *rgba, int width, int height) {
  if (display_ == nullptr || rgba == nullptr || width <= 0 || height <= 0) {
    return false;
  }

  EGLDisplay display = static_cast<EGLDisplay>(display_);
  if (!eglMakeCurrent(display, static_cast<EGLSurface>(eglSurface_), static_cast<EGLSurface>(eglSurface_),
                      static_cast<EGLContext>(context_))) {
    return false;
  }

  width_ = width;
  height_ = height;

  glViewport(0, 0, width, height);
  glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
  glClear(GL_COLOR_BUFFER_BIT);

  // The guest frame is uploaded as a texture, so the compositor path is the
  // same once the Wayland host is connected to this surface.
  glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
  glActiveTexture(GL_TEXTURE0);
  glBindTexture(GL_TEXTURE_2D, texture_);
  glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, width, height, 0, GL_RGBA, GL_UNSIGNED_BYTE, rgba);

  receivingFrames_ = true;
  return true;
}

void SurfaceHost::SwapBuffers() {
  if (display_ == nullptr) {
    return;
  }
  eglSwapBuffers(static_cast<EGLDisplay>(display_), static_cast<EGLSurface>(eglSurface_));
}

// --- Guest frame rendering -----------------------------------------------

namespace {

/**
 * BGRA is not a core GLES3 format, so the extension constant is used when
 * the driver advertises it and the pixels are swapped on the CPU when it
 * does not.
 */
#ifndef GL_BGRA_EXT
#define GL_BGRA_EXT 0x80E1
#endif

/** True when the driver can upload BGRA directly. */
bool DriverHasBgra() {
  static int supported = -1;
  if (supported >= 0) {
    return supported == 1;
  }
  const char *extensions = reinterpret_cast<const char *>(glGetString(GL_EXTENSIONS));
  supported = 0;
  if (extensions != nullptr) {
    const std::string list(extensions);
    supported = (list.find("GL_EXT_texture_format_BGRA8888") != std::string::npos ||
                 list.find("GL_OES_texture_format_BGRA8888") != std::string::npos)
                    ? 1
                    : 0;
  }
  return supported == 1;
}

/** Swaps BGRA into RGBA, which every GLES3 driver accepts. */
void SwapBgraToRgba(const uint8_t *source, int width, int height, int stride, uint8_t *destination) {
  for (int row = 0; row < height; ++row) {
    const uint8_t *in = source + static_cast<size_t>(row) * stride;
    uint8_t *out = destination + static_cast<size_t>(row) * width * 4;
    for (int column = 0; column < width; ++column) {
      out[column * 4 + 0] = in[column * 4 + 2];
      out[column * 4 + 1] = in[column * 4 + 1];
      out[column * 4 + 2] = in[column * 4 + 0];
      out[column * 4 + 3] = in[column * 4 + 3];
    }
  }
}

/** Swaps RGBA back into BGRA. */
void SwapRgbaToBgra(const uint8_t *source, int width, int height, uint8_t *destination) {
  for (int row = 0; row < height; ++row) {
    const uint8_t *in = source + static_cast<size_t>(row) * width * 4;
    uint8_t *out = destination + static_cast<size_t>(row) * width * 4;
    for (int column = 0; column < width; ++column) {
      out[column * 4 + 0] = in[column * 4 + 2];
      out[column * 4 + 1] = in[column * 4 + 1];
      out[column * 4 + 2] = in[column * 4 + 0];
      out[column * 4 + 3] = in[column * 4 + 3];
    }
  }
}

/** Minimal GLES3 shader that draws a texture over the whole viewport. */
const char *kVertexShader = R"(#version 300 es
in vec2 aPosition;
in vec2 aTexCoord;
out vec2 vTexCoord;
void main() {
  gl_Position = vec4(aPosition, 0.0, 1.0);
  vTexCoord = aTexCoord;
}
)";

const char *kFragmentShader = R"(#version 300 es
precision mediump float;
in vec2 vTexCoord;
uniform sampler2D uTexture;
out vec4 fragColor;
void main() {
  fragColor = texture(uTexture, vTexCoord);
}
)";

GLuint CompileShader(GLenum type, const char *source) {
  GLuint shader = glCreateShader(type);
  if (shader == 0) {
    return 0;
  }
  glShaderSource(shader, 1, &source, nullptr);
  glCompileShader(shader);
  GLint compiled = 0;
  glGetShaderiv(shader, GL_COMPILE_STATUS, &compiled);
  if (compiled == 0) {
    DDK_LOGE("the guest frame shader did not compile");
    glDeleteShader(shader);
    return 0;
  }
  return shader;
}

/** Builds the texture program once, on first use. */
GLuint TextureProgram() {
  static GLuint program = 0;
  static bool tried = false;
  if (tried) {
    return program;
  }
  tried = true;

  const GLuint vertex = CompileShader(GL_VERTEX_SHADER, kVertexShader);
  const GLuint fragment = CompileShader(GL_FRAGMENT_SHADER, kFragmentShader);
  if (vertex == 0 || fragment == 0) {
    return 0;
  }

  program = glCreateProgram();
  glAttachShader(program, vertex);
  glAttachShader(program, fragment);
  glBindAttribLocation(program, 0, "aPosition");
  glBindAttribLocation(program, 1, "aTexCoord");
  glLinkProgram(program);

  GLint linked = 0;
  glGetProgramiv(program, GL_LINK_STATUS, &linked);
  if (linked == 0) {
    DDK_LOGE("the guest frame program did not link");
    glDeleteProgram(program);
    program = 0;
    return 0;
  }

  glDeleteShader(vertex);
  glDeleteShader(fragment);
  return program;
}

}  // namespace

unsigned int SurfaceHost::CreateTexture(int width, int height) {
  if (display_ == nullptr || width <= 0 || height <= 0) {
    return 0;
  }
  EGLDisplay display = static_cast<EGLDisplay>(display_);
  if (!eglMakeCurrent(display, static_cast<EGLSurface>(eglSurface_), static_cast<EGLSurface>(eglSurface_),
                      static_cast<EGLContext>(context_))) {
    return 0;
  }

  GLuint texture = 0;
  glGenTextures(1, &texture);
  glBindTexture(GL_TEXTURE_2D, texture);
  glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
  glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
  glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
  glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
  glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, width, height, 0, GL_RGBA, GL_UNSIGNED_BYTE, nullptr);
  return texture;
}

bool SurfaceHost::UploadTexture(unsigned int texture, const void *data, int width, int height, int stride) {
  if (display_ == nullptr || texture == 0 || data == nullptr || width <= 0 || height <= 0) {
    return false;
  }
  EGLDisplay display = static_cast<EGLDisplay>(display_);
  if (!eglMakeCurrent(display, static_cast<EGLSurface>(eglSurface_), static_cast<EGLSurface>(eglSurface_),
                      static_cast<EGLContext>(context_))) {
    return false;
  }

  glBindTexture(GL_TEXTURE_2D, texture);
  // The guest's shm buffers are BGRA with a stride that can be wider than
  // the image, so the row length has to be given to the driver.
  const int rowLength = stride > 0 ? stride / 4 : 0;
  glPixelStorei(GL_UNPACK_ROW_LENGTH, rowLength);
  glPixelStorei(GL_UNPACK_ALIGNMENT, 1);

  if (DriverHasBgra()) {
    glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, width, height, GL_BGRA_EXT, GL_UNSIGNED_BYTE, data);
    glPixelStorei(GL_UNPACK_ROW_LENGTH, 0);
    return true;
  }

  // No BGRA support: swap the channels on the CPU and upload RGBA.
  std::vector<uint8_t> swapped(static_cast<size_t>(width) * height * 4);
  SwapBgraToRgba(static_cast<const uint8_t *>(data), width, height, stride, swapped.data());
  glPixelStorei(GL_UNPACK_ROW_LENGTH, 0);
  glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, width, height, GL_RGBA, GL_UNSIGNED_BYTE, swapped.data());
  return true;
}

bool SurfaceHost::DrawTexture(unsigned int texture, int width, int height) {
  return DrawTextureRect(texture, 0.0f, 0.0f, 1.0f, 1.0f, 0, 0, width, height);
}

bool SurfaceHost::DrawTextureRect(unsigned int texture, float sx, float sy, float sw, float sh,
                                  int dx, int dy, int dw, int dh) {
  if (display_ == nullptr || texture == 0) {
    return false;
  }
  EGLDisplay display = static_cast<EGLDisplay>(display_);
  EGLSurface eglSurface = static_cast<EGLSurface>(eglSurface_);
  if (!eglMakeCurrent(display, eglSurface, eglSurface, static_cast<EGLContext>(context_))) {
    return false;
  }

  EGLint surfaceWidth = 0;
  EGLint surfaceHeight = 0;
  eglQuerySurface(display, eglSurface, EGL_WIDTH, &surfaceWidth);
  eglQuerySurface(display, eglSurface, EGL_HEIGHT, &surfaceHeight);
  if (surfaceWidth <= 0 || surfaceHeight <= 0) {
    return false;
  }

  const GLuint program = TextureProgram();
  if (program == 0) {
    return false;
  }

  // The destination rectangle is converted into clip space, with the origin
  // at the bottom left.
  const float left = (static_cast<float>(dx) / surfaceWidth) * 2.0f - 1.0f;
  const float right = (static_cast<float>(dx + dw) / surfaceWidth) * 2.0f - 1.0f;
  const float top = 1.0f - (static_cast<float>(dy) / surfaceHeight) * 2.0f;
  const float bottom = 1.0f - (static_cast<float>(dy + dh) / surfaceHeight) * 2.0f;

  const float vertices[] = {
      left, bottom, sx, sy + sh,
      right, bottom, sx + sw, sy + sh,
      left, top, sx, sy,
      right, top, sx + sw, sy,
  };

  GLuint vertexBuffer = 0;
  glGenBuffers(1, &vertexBuffer);
  glBindBuffer(GL_ARRAY_BUFFER, vertexBuffer);
  glBufferData(GL_ARRAY_BUFFER, sizeof(vertices), vertices, GL_STATIC_DRAW);

  glUseProgram(program);
  glActiveTexture(GL_TEXTURE0);
  glBindTexture(GL_TEXTURE_2D, texture);
  glUniform1i(glGetUniformLocation(program, "uTexture"), 0);

  glEnableVertexAttribArray(0);
  glVertexAttribPointer(0, 2, GL_FLOAT, GL_FALSE, 4 * sizeof(float), nullptr);
  glEnableVertexAttribArray(1);
  glVertexAttribPointer(1, 2, GL_FLOAT, GL_FALSE, 4 * sizeof(float),
                         reinterpret_cast<const void *>(2 * sizeof(float)));

  glViewport(0, 0, surfaceWidth, surfaceHeight);
  glEnable(GL_BLEND);
  glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
  glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
  glDisable(GL_BLEND);

  glDisableVertexAttribArray(0);
  glDisableVertexAttribArray(1);
  glBindBuffer(GL_ARRAY_BUFFER, 0);
  glDeleteBuffers(1, &vertexBuffer);
  return true;
}

bool SurfaceHost::ReadTexture(unsigned int texture, int width, int height, void *out) {
  if (display_ == nullptr || texture == 0 || out == nullptr || width <= 0 || height <= 0) {
    return false;
  }
  EGLDisplay display = static_cast<EGLDisplay>(display_);
  if (!eglMakeCurrent(display, static_cast<EGLSurface>(eglSurface_), static_cast<EGLSurface>(eglSurface_),
                      static_cast<EGLContext>(context_))) {
    return false;
  }

  glBindTexture(GL_TEXTURE_2D, texture);
  glPixelStorei(GL_PACK_ALIGNMENT, 1);

  // The compositor works in BGRA, so the readback is converted back.
  std::vector<uint8_t> rgba(static_cast<size_t>(width) * height * 4);
  glReadPixels(0, 0, width, height, GL_RGBA, GL_UNSIGNED_BYTE, rgba.data());
  SwapRgbaToBgra(rgba.data(), width, height, static_cast<uint8_t *>(out));
  return true;
}

void SurfaceHost::DeleteTexture(unsigned int texture) {
  if (display_ == nullptr || texture == 0) {
    return;
  }
  EGLDisplay display = static_cast<EGLDisplay>(display_);
  if (!eglMakeCurrent(display, static_cast<EGLSurface>(eglSurface_), static_cast<EGLSurface>(eglSurface_),
                      static_cast<EGLContext>(context_))) {
    return;
  }
  glDeleteTextures(1, &texture);
}

void *SurfaceHost::Window() const {
  return window_;
}

void SurfaceHost::Size(int *width, int *height) const {
  int w = 0;
  int h = 0;
  if (display_ != nullptr) {
    eglQuerySurface(static_cast<EGLDisplay>(display_), static_cast<EGLSurface>(eglSurface_), EGL_WIDTH, &w);
    eglQuerySurface(static_cast<EGLDisplay>(display_), static_cast<EGLSurface>(eglSurface_), EGL_HEIGHT, &h);
  }
  if (width != nullptr) {
    *width = w;
  }
  if (height != nullptr) {
    *height = h;
  }
}

void SurfaceHost::Clear() {
  if (display_ == nullptr) {
    return;
  }
  EGLDisplay display = static_cast<EGLDisplay>(display_);
  if (!eglMakeCurrent(display, static_cast<EGLSurface>(eglSurface_), static_cast<EGLSurface>(eglSurface_),
                      static_cast<EGLContext>(context_))) {
    return;
  }
  int width = 0;
  int height = 0;
  eglQuerySurface(display, static_cast<EGLSurface>(eglSurface_), EGL_WIDTH, &width);
  eglQuerySurface(display, static_cast<EGLSurface>(eglSurface_), EGL_HEIGHT, &height);
  glViewport(0, 0, width, height);
  glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
  glClear(GL_COLOR_BUFFER_BIT);
}

}  // namespace ddk

// The ArkUI framework calls these entry points on the module named by the
// XComponent's `libraryname` option.
extern "C" void OnSurfaceCreated(OH_NativeXComponent *component, void *window) {
  (void)component;
  uint64_t width = 0;
  uint64_t height = 0;
  OH_NativeXComponent_GetXComponentSize(component, window, &width, &height);
  ddk::SurfaceHost::Instance().OnSurfaceCreated(window, static_cast<int32_t>(width),
                                                static_cast<int32_t>(height));
}

extern "C" void OnSurfaceChanged(OH_NativeXComponent *component, void *window) {
  uint64_t width = 0;
  uint64_t height = 0;
  OH_NativeXComponent_GetXComponentSize(component, window, &width, &height);
  ddk::SurfaceHost::Instance().OnSurfaceChanged(window, static_cast<int32_t>(width),
                                                static_cast<int32_t>(height));
}

extern "C" void OnSurfaceDestroyed(OH_NativeXComponent *component, void *window) {
  (void)component;
  (void)window;
  ddk::SurfaceHost::Instance().OnSurfaceDestroyed();
}
