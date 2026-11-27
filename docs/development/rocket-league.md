# Rocket League compatibility profile

Steam app 252950 uses the selected Proton's bundled x86-64 DXVK DLLs beside
RocketLeague.exe when that Proton ships native ARM64 DXVK. This avoids a measured
startup failure in DroidDeck-Proton 11-12.8 / DXVK v3.1.1-225-g7046b43a: exception
0xc000001d at dxgi.dll RVA 0x338a10, followed by a rendering-thread fatal error.
The instruction at that address is an ordinary ARM64 STR. An x64/native entry
problem is suspected; the root cause has not been established.

The launch wrapper validates all three DLL architectures before installing them.
It records hashes, refreshes its own copies when the bundled component changes,
and preserves existing or modified game-local DLLs, including case variants. Proton's shared installation
and prefix graphics DLLs are unchanged. Other games and prefix maintenance
commands do not use the profile. Explicit graphics DLL overrides, WineD3D,
x64 Wine, and the PowerVR runtime take precedence.

To opt out, set `DROIDDECK_ROCKET_LEAGUE_X64_DXVK=0` in Rocket League's Game
environment and relaunch. The next launch removes unchanged files owned by the
profile. Modified files and their ownership records are preserved for manual review;
unchanged owned files are still removed independently. Resetting the game
profile restores the default automatic workaround. A crash during installation
can leave unowned copies; the wrapper preserves those rather than overwriting
files it cannot prove it owns.

## Memory-constrained devices

An Adreno 740 / 16 GB device passed the native-DXVK failure with x64 DXVK, then
Android killed the DroidDeck app with exit reason LOW_MEMORY. A subsequent run
reached the Play menu and the user confirmed it worked with these settings:

- Resolution 960 x 540, 30 fps cap.
- Texture groups World, Character and Vehicle (including normal/specular maps):
  MaxLODSize=512, LODBias=1.
- DetailMode=0; MaxShadowResolution=512;
  MaxWholeSceneDominantShadowResolution=512.
- DynamicShadows, LightEnvironmentShadows, MotionBlur, DepthOfField,
  AmbientOcclusion, Bloom, Distortion, AllowImageReflections,
  AllowImageReflectionShadowing, bAllowHighQualityMaterials=False.
- Steam Overlay disabled; detailed Proton/DXVK logging disabled.

These are a conservative fallback, not automatic changes to existing graphics
preferences. Several settings and logging changed together; their individual
contributions were not isolated. Match stability, other devices, online play and
anti-cheat compatibility have not been established by this test. The automatic
profile does not disable anti-cheat or Steam Overlay. Users needing this memory
fallback should back up TASystemSettings.ini before applying it and can increase
quality gradually after a successful launch.

Session scripts ship in APK assets: this launch-wrapper change requires an updated
APK, not a Lab build. Local app-side DLLs used during diagnosis are not proof that
the new wrapper ran; verify its launch log message on a clean installation.
