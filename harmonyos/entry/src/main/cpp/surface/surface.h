#ifndef DDK_SURFACE_H
#define DDK_SURFACE_H

#include <cstdint>

namespace ddk {

/**
 * Publishes the guest desktop into an ArkUI XComponent.
 *
 * Upstream Android runs a Wayland server inside the app: the Linux guest
 * draws into it and the resulting frames are shown on a SurfaceTexture.
 * HarmonyOS has no SurfaceTexture, so the port renders into the native
 * window of an XComponent instead.
 *
 * The ArkUI framework hands the window to the module through the native
 * XComponent plugin entry points (`OnSurfaceCreated` and friends), which
 * live in surface.cpp. The EGL plumbing, the swap chain and the frame
 * publishing API live here; the Wayland server that produces frames is the
 * part that plugs into PublishFrame().
 */
class SurfaceHost {
 public:
  static SurfaceHost &Instance();

  /** The framework reports that the XComponent's surface now exists. */
  void OnSurfaceCreated(void *window, int32_t width, int32_t height);

  /** The framework reports that the surface changed size. */
  void OnSurfaceChanged(void *window, int32_t width, int32_t height);

  /** The framework reports that the surface went away. */
  void OnSurfaceDestroyed();

  /** Creates the EGL context and window surface. */
  bool Attach();

  void Detach();

  bool IsAttached() const;

  /** True once a frame has been published, i.e. the guest is drawing. */
  bool IsReceivingFrames() const;

  /** Size of the surface the guest is drawing into. */
  int32_t Width() const;
  int32_t Height() const;

  /**
   * Publishes a frame of guest output.
   *
   * @param rgba tightly packed RGBA pixels
   * @param width frame width in pixels
   * @param height frame height in pixels
   */
  bool PublishFrame(const uint8_t *rgba, int width, int height);

  /** Swaps the current frame to the screen. */
  void SwapBuffers();

  // --- Guest frame rendering -------------------------------------------
  //
  // The compositor keeps the guest's frames in textures, so the surface
  // host also owns the texture helpers. HarmonyOS guarantees EGL and
  // GLES but not Vulkan, which is why the compositor's images are
  // textures here rather than Vulkan images.

  /** Creates a texture for guest frames. Returns 0 on failure. */
  unsigned int CreateTexture(int width, int height);

  /**
   * Uploads BGRA pixels into a texture.
   *
   * @param stride bytes per row, which may exceed width * 4
   */
  bool UploadTexture(unsigned int texture, const void *data, int width, int height, int stride);

  /** Draws a texture over the whole surface. */
  bool DrawTexture(unsigned int texture, int width, int height);

  /**
   * Draws part of a texture into a rectangle of the surface.
   *
   * @param sx source rectangle, in fractions of the texture
   * @param dx destination rectangle, in pixels
   */
  bool DrawTextureRect(unsigned int texture, float sx, float sy, float sw, float sh,
                       int dx, int dy, int dw, int dh);

  /** Reads a texture back into a BGRA buffer. */
  bool ReadTexture(unsigned int texture, int width, int height, void *out);

  /** Releases a texture. */
  void DeleteTexture(unsigned int texture);

  /** The native window the surface was created from, or null. */
  void *Window() const;

  /** Width and height of the surface in pixels. */
  void Size(int *width, int *height) const;

  /** Clears the surface to black. */
  void Clear();

 private:
  SurfaceHost() = default;

  bool CreateEgl();

  void *window_ = nullptr;  // OHNativeWindow *
  void *display_ = nullptr;  // EGLDisplay
  void *context_ = nullptr;  // EGLContext
  void *eglSurface_ = nullptr;  // EGLSurface
  unsigned int texture_ = 0;
  bool receivingFrames_ = false;
  int32_t width_ = 0;
  int32_t height_ = 0;
};

}  // namespace ddk

#endif  // DDK_SURFACE_H
