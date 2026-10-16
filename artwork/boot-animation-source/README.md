# DroidDeck boot animation

This source accompanies the Steam startup movies bundled by the app:
`app/src/main/assets/steam-startup/droiddeck-startup.webm` (1280×720 at 30 fps) and
`droiddeck-startup-60.webm` (1280×720 at 60 fps), both with a pitch black background, VP8 video
and Opus audio. `droiddeck-boot.html` is the editable animation; the scripts and WAV below
render the animation at the bundled playback profiles. No alternate MP4 exports are kept in the repository.

From the repository root, render the movies with:

    python3 artwork/boot-animation-source/render.py 1280 720 30 app/src/main/assets/steam-startup/droiddeck-startup.webm dark
    python3 artwork/boot-animation-source/render.py 1280 720 60 app/src/main/assets/steam-startup/droiddeck-startup-60.webm dark

The 30 fps profile reduces decoded pixels per second by 78% compared with 1080p/60, and VP8
lowers software decode cost while Steam is starting, so 30 fps stays the default. Keep the bundled
movies at 720p; higher-resolution exports are intended for sharing.

Steam settings > Boot movie picks which movie DroidDeck stages into Steam's
`config/uioverrides/movies` folder before each session: DroidDeck at 30 or 60 fps, or a movie the
user imported. A WebM, MKV or MP4 of up to 50 MB with VP8 or VP9 video (or a WebM with AV1) is used
as is, remuxed to WebM if needed. Anything else Android can decode, up to 60 seconds, is converted on
the device to 720p VP8 with Opus audio, capped at 60 fps; portrait clips are pillarboxed into 16:9
because Steam fills the screen with the movie. Turning Sound off strips the audio track on the device.
If Steam still has its built-in startup movie selected, DroidDeck sets the staged movie as the
device's startup default before Steam starts. A startup movie the user selected later in Steam is
preserved.

To regenerate the soundtrack, run these commands from this directory:

    python3 events.py
    python3 sound.py

The animation and audio are both procedurally generated; the included WAV is the soundtrack used
for the bundled render.

`droiddeck-boot.html` holds the whole animation. Every frame is a pure function of time, so the
preview page and the video render draw exactly the same thing. Open it in a browser to scrub, slow
it down or step frame by frame (arrow keys).

## Re-render the video

    pip install playwright && playwright install chromium   # ffmpeg with libvpx also required
    python3 render.py 1280 720 30 droiddeck-boot-1280x720.webm
    python3 render.py 1920 1080 60 droiddeck-boot-1920x1080.mp4    # .mp4 = H.264 for sharing
    python3 render.py 1280 720 30 droiddeck-boot-dark-1280x720.webm dark   # dark version

Each frame gets real motion blur (180° shutter, adaptive sub-frames). Output is VP8 for .webm or H.264 for .mp4, both BT.709,
so the orb stays #1A9FFF after compression.

## Sound

Every sound is synthesized in `sound.py` (no samples) and placed on the exact physics events the
animation reports, so hits stay frame-accurate even after you change the timing. Key: D major.

    python3 events.py         # export impact times, rocking, piece speeds from the animation
    python3 sound.py          # -> droiddeck-boot-sound.wav (-18 LUFS, -1 dBFS peak)
    python3 embed_sound.py    # put it in the preview page
    python3 render.py ...     # muxes the wav in automatically (Opus in .webm, AAC in .mp4)

The soundtrack is built from the final chord. Each piece lands on one of its notes as a soft, muted
tone (legs D, ball A with a quieter echo, bowl F#), the wobble and jump play in silence, and the
lock lands with the same soft voice and plays all the notes together. Tweak the cue sheet in `sound.py`: the `tock(...)` calls under
`# 1. the stack`, the chord list under `# 4. lock`, and the room reverb mix (`.14 * wet`).

## Where to tweak

- Timing of the drops: `archDrop`, `ballDrop`, `domeDrop` (start time, height, bounciness `e`).
- The balancing act: `SWAY` keyframes (time, lean). Bigger values = bigger wobble. The bowl leads,
  the ball chases it (`sway(t - .12)`), the legs follow (`sway(t - .10)`).
- The catch-hop on the big wobble: `HOP` (start time, duration, sideways distance, height).
- Crouch before the jump: `crouchA/B/D`.
- Jump and lock-in: `FL` (rise time, apex height, lock time, how far pieces spread).
- Colors for each version (pieces, background, shadows, lock glow): `THEMES` (light, dark).
- Total length: `DUR`.

## Install as the Steam startup movie

Put the .webm in `~/.steam/root/config/uioverrides/movies/`, then pick it in
Steam > Settings > Customization > Startup Movie.
