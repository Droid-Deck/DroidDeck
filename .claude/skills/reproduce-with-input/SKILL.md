---
name: reproduce-with-input
description: Reproduce a reported game problem by launching the game and driving controller input
---

1. `droiddeckctl state` first. If the game already has focus, do not launch it again: Steam shows
   "Game already running" and `launch` times out. With no session, `droiddeckctl start steam --wait`.
   Then `droiddeckctl launch <appid> --wait` and `droiddeckctl wait focused <appid> --timeout 180`.
   Check `droiddeckctl thermal` too: at `SEVERE` or worse, performance results mean little.
2. Look before acting: `droiddeckctl screenshot ./shot.png --display 0`, then read the image. On the
   Retroid Pocket Duo the top screen is display 0 and the bottom is 2 (`droiddeckctl displays`).
   A game shows black or loading frames for a while after it takes focus; take another shot a few
   seconds later before calling it a black screen.
3. Drive it with `droiddeckctl input`: `pad a` (also `b x y lb rb start select l3 r3 guide qam`
   and the D-pad `up down left right`; `--hold` in ms), `stick left 0 -1 --hold 800`,
   `trigger right 1.0`, `key ENTER`, `text 'hello'`. Input goes to the game window that has
   focus once the session has drawn a frame. The launcher's own screens need
   `adb -s $ADB_SERIAL shell input keyevent ...` instead. Take a screenshot after each step that
   should change the screen.
4. Measure: `droiddeckctl pacing --seconds 20` (`fpsOnScreen`, `offCadencePercent`). For flicker
   or stutter a still cannot show, `droiddeckctl record ./clip.mp4 --seconds 8 --display 0`.
5. Make it replayable: write the steps as a scenario and run it with `tools/droiddeck-scenario`
   from the checkout (not on PATH); `--help` documents the format. Give screenshot steps a display:

   ```json
   {"name": "nfs-menu", "vars": {"appId": 47870}, "repeat": 1,
    "before": [["start", "steam", "--wait", "--reuse", "--timeout", "240"]],
    "steps": [["launch", "${appId}", "--wait"],
              ["wait", "focused", "${appId}", "--timeout", "180"],
              {"sleep": 20}, ["input", "pad", "a"], {"sleep": 3},
              {"screenshot": "after-a", "display": 0},
              ["pacing", "--seconds", "10"], {"assert": "fpsOnScreen", "exists": true}],
    "after": [["quit", "${appId}"]]}
   ```

   `tools/droiddeck-scenario nfs-menu.json --out ./runs` writes `report.json` and, for a failed
   run, a screenshot, focus and logs.
6. Report the steps that reproduce it and what you saw at each. Quit the game with
   `droiddeckctl quit <appid>` unless the user is playing it. With "Return after game" on (the
   default), quitting a game DroidDeck launched also ends the session, so `start steam` again
   before the next launch.
