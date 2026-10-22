# Tracked DroidDeck menus

When glasses output and the tracked virtual screen are enabled, DroidDeck mirrors its own foreground menus into a glasses `Presentation`. Phone controls remain on the phone. The menu renderer does not start the guest compositor, request MediaProjection, or capture another application's windows.

`GlassesMenuCapture` draws the activity's visible view roots into a hardware Canvas at up to 30 fps. `GlassesMenuSurface` consumes that buffer as an external-OES texture and redraws the screen at the glasses refresh rate. The plane geometry follows the existing `cardboard.frag`: row-major head-to-anchor rotation, metres for translation, +Y up, -Z forward, and a top-left source image. Mono uses a central eye; SBS renders the full UI to both eyes with 64 mm eye separation.

The pose adapter keeps its 3DoF reference across menu pages and uses the shared spatial **screen** origin for 6DoF. Menu recenter does not change the VR floor origin. Stale or invalid tracking holds the last safe view. Explicitly changing between 3DoF and 6DoF can reanchor the screen because those modes have separate origins; recenter restores the chosen mode's view.

On Android 29 and later, capture includes visible app dialog/popup roots exposed by `WindowInspector`. Android 26–28 captures the activity decor only, so separate dialog and popup windows are not mirrored. System keyboards, system permission prompts, other apps, and separately composited `SurfaceView` content are outside this capture path. Secure windows are excluded. Phone interaction remains available for these cases.

## Shader regression check

The opt-in Python check compiles the actual embedded GLES shaders, imports an asymmetric texture through EGLImage/external-OES, renders it, and checks readback pixels. It covers image orientation, both SBS eyes, translation, yaw, and views behind the screen. It does not require Python packages beyond the standard library and is not part of default pytest discovery.

On Debian/Ubuntu with Python 3:

```sh
sudo apt-get install libegl1 libegl-mesa0 libgles2 libgl1-mesa-dri
python3 tools/check_glasses_menu_shader.py
```

Run from any working directory by specifying the script's path. `--output-dir PATH` overrides the default `build/glasses-menu-shader`. The check defaults to headless Mesa software rendering and GLES 2.0. It writes `menu-shader-validation.json` with the shader hashes, GL version, and pixel comparisons, plus `menu-shader-mono.png` and `.ppm`.

The test supplies the Android consumer's vertical-flip texture transform. It verifies the real shader under that transform; it does not replace checking Android's actual Canvas → SurfaceTexture producer/consumer integration or display orientation on the phone.

## Phone and glasses smoke test

1. Connect supported glasses, open DroidDeck's setup page, and enable glasses output and the tracked virtual screen. Confirm the phone remains touchable and text in the glasses is upright with the same left/right ordering.
2. Switch among Steam and non-Steam launcher pages without launching a game. Scroll, open menus and app dialogs, and change a setting from the phone. Check that the glasses follow the current page and retain the screen's head-tracked anchor.
3. In 3DoF, turn and tilt the glasses, then use Recenter. Test mono and SBS. Each SBS eye must contain the full UI with matching vertical alignment.
4. Enable 6DoF on supported hardware and complete its setup. Translate left/right, up/down, and toward/away from the plane. Recenter the menu and verify that VR floor height has not changed. Briefly obscure tracking; the held menu must remain finite and stable until tracking recovers.
5. Launch a game, open and close the session menu, then return to the launcher. Confirm clean ownership handoff between the game output and menu presentation and that phone-only operation still works.
6. Background/foreground DroidDeck, rotate the phone, change the glasses display mode, and unplug/replug the glasses. Check for stale frames, black output that persists after reopening, crashes, or a reused orientation from the previous device connection.

For evidence, `tools/droiddeckctl displays` lists logical display IDs and `tools/droiddeckctl screenshot ./glasses.png --display <id>` captures the selected output. Inspect `GlassesMenu` log messages for capture or renderer failures. A display screenshot verifies image content; head movement and stereo comfort still require the physical glasses.
