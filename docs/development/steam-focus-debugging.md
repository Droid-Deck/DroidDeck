# Steam session focus: how it works and how to debug it

Written 2026-10-07 after the "game opens behind Steam" reports (Skyrim SE, A Plague Tale:
Innocence, Burnout Paradise Remastered), fixed by gamescope patch 0114 (#401). It records what a
game needs to get focus in a Steam session and how to inspect a live session on a device, for the
next report that looks like this.

## How a game gets focus

The Steam session runs two Xwayland servers, as SteamOS does: the client on `:0`, games on `:1`
(`--xwayland-count 2`, `STEAM_MULTIPLE_XWAYLANDS=1` in `droiddeck-session`). Focus is decided in
three steps, and a window can drop out at each one:

1. **gamescope lists candidates.** For each server it collects windows that are mapped, of class
   InputOutput, not fully transparent (`_NET_WM_WINDOW_OPACITY` above 0), not an overlay, and
   carry an app id (`STEAM_GAME`). It publishes them on `:0`'s root as `GAMESCOPE_FOCUSABLE_APPS`
   and `GAMESCOPE_FOCUSABLE_WINDOWS` (window, app id, pid triples). It leaves out of that published
   list any window it believes is override-redirect, 1x1, or skip-taskbar-and-pager without
   fullscreen.
2. **Steam picks an order.** The client reads those lists and writes
   `GAMESCOPECTRL_BASELAYER_APPID`, for example `413091, 489830, 769`. When the running game has
   no window in the focusable list, Steam puts itself (769) ahead of it.
3. **gamescope follows Steam.** The virtual connector strategy in a Steam session is
   SteamControlled: the first app id in the base layer with a candidate window wins.

gamescope works from its own cached copy of each window's attributes, not from X. A window that X
shows mapped and tagged can still be missing in step 1 if that cache is stale.

## What the bug looked like

- On screen: Steam's black loading screen with only **ABORT GAME**, the game audible underneath.
  Resume from the Steam menu does not help.
- `GAMESCOPE_FOCUSED_APP` stays `769`; `GAMESCOPECTRL_BASELAYER_APPID` has `769` ahead of the
  game's app id; the game's window is missing from `GAMESCOPE_FOCUSABLE_WINDOWS`.
- On `:1` the game window is mapped, `WM_STATE` Normal, `STEAM_GAME` set, with no opacity,
  `_NET_WM_STATE` or overlay properties.
- gamescope's `focus_info` shows server 1's own focus on the game while the global focus is Steam.

Cause: wine creates the game's window override-redirect and makes it managed before mapping it,
which sends no X event. gamescope read the attributes when the window was created and refreshed
`override_redirect` only from ConfigureNotify, so when its read came before wine's change it kept
the window as override-redirect and left it out of the list Steam reads. It is a race: Skyrim
failed about once in six launches on the Thor, on the first launch after a session start. Upstream
fixed it by taking the flag from MapNotify (ValveSoftware/gamescope 3829340, our patch 0114).

The launcher and the game both had window id `0x1c00003` on `:1`: with few clients on that server
the X server hands the game the launcher's freed id. That was not the cause (the game window came
7 s after the launcher's was destroyed), but it makes logs easy to misread.

## Inspecting a live session from adb

Everything below runs on the device as the app's uid (`su 10195` on the Thor, `u0_a195`), using
the rootfs's own binaries through its loader, so nothing has to enter proot:

```sh
R=/data/user/0/com.droiddeck.launcher/files/linuxfs
$R/usr/lib/ld-linux-aarch64.so.1 --library-path $R/usr/lib $R/usr/bin/xprop -display :0 -root \
  GAMESCOPE_FOCUSED_APP GAMESCOPE_FOCUSABLE_WINDOWS GAMESCOPECTRL_BASELAYER_APPID
```

- The X servers answer on their **abstract** sockets (`\0/tmp/.X11-unix/X0`, `X1`); xprop finds
  them by itself. The socket files under `linuxfs/tmp/.X11-unix` can be stale. Nothing answers
  while the session is in the background or the screen is off (the session is stopped).
- The `VAR=… cmd` prefix does not survive `timeout` or `nohup`; put the command in a small
  `/system/bin/sh` script in `/data/local/tmp` and run that.
- gamescope's focus state, written to the session's `session.log` (in
  `Download/DroidDeck/<date>-NN-steam/`):

  ```sh
  export XDG_RUNTIME_DIR=/data/user/0/com.droiddeck.launcher/files/.wayland-rt WAYLAND_DISPLAY=gamescope-0
  G="$R/usr/lib/ld-linux-aarch64.so.1 --library-path $R/usr/lib $R/usr/bin/gamescopectl"
  $G log_focus debug; $G focus_info
  ```

  It prints the global focus, input, keyboard and override windows, the connector strategy, and
  each server's own focus and keyboard focus. Disagreement between a server's focus and the
  global one points at step 1 or 2 above.
- To tell a stale gamescope cache from a real X problem, make gamescope re-read the window: move it
  by 1 px and back (ConfigureNotify), or unmap and map it (MapNotify). If the game comes forward,
  gamescope's cache was wrong. A restack of a window already on top sends no event and proves
  nothing.
- To test gamescope with a candidate binary without rebuilding the apk, put it in the rootfs (for
  example `/opt/gstest/gamescope`, owned by the app's uid) and add
  `PATH=/opt/gstest:/usr/local/bin:/usr/bin:/bin` to `Download/droiddeck-env`. The session log's
  first lines name the gamescope that ran. Remove the line afterwards: the app stages its own
  gamescope over `/usr/local/bin` at every session start.
- Never `pkill -f <pattern>` inside `su -c '…'`: the pattern matches that shell's own command line.
  Killing `SkyrimSE.exe` that way took the whole session down. Kill by pid.
- Skyrim's menus take adb key events (`input keyevent 20` down, `66` enter); taps do nothing.
