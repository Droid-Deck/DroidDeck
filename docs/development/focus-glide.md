# Focus transitions

Focusable app controls participate in the shared focus glide. Nearby moves stretch
the outline; jumps over 360 dp collapse it into a ball before expanding at the
destination. Rapid repeats keep the faster outline movement. Disabling animations
places the outline directly at the focused control.

`DroidDeckTheme` supplies the host for standalone Compose interfaces. Custom
controls use `glideBorder` with their focus interaction state. Material controls
use `Modifier.focusGlide(shape)` before their focus target. Match the control's
shape, and keep existing selection borders separate from the focus outline.
Filled primary buttons use `hotFillAlpha = 0f` to preserve label contrast.

Use `GlideDialog`, `GlideAlertDialog`, `GlidePopup`, and `GlideDropdownMenu` for app
windows. Android dialogs and popups have separate views and coordinate spaces;
they need their own host. The wrappers preserve the underlying Material layouts
and keep all controls in each window on the same animation. Dropdown hosts also
handle recreation of the popup view after closing.

Touch dismiss backdrops and controller shortcut hints should have
`canFocus = false`. Controls with a larger touch target can register the visible
shape separately, as the toggle switch and pause/resume orb do. Web content,
Android system UI, and native game input surfaces retain their own focus behavior.

`FocusGlideWindowsTest` checks Material controls, Xbox menu entries, dialog focus
handoffs, and dropdown reopening. `FocusGlideRenderingTest` checks the large-jump
ball animation and outline-only rendering with animations disabled.
