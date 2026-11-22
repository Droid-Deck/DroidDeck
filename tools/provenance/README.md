# Tracked binary provenance

This is an **exception inventory**, not evidence that every distributed binary is
built from source. `prebuilt.json` hashes opaque, checked-in payloads and records
their known upstream *project*, not a verified corresponding source revision.

Run `python3 tools/provenance/check.py` locally. CI runs the same gate after
merging a PR onto `main`, before the component builds. Adding or modifying any
tracked native executable, shared library, compressed runtime archive, model,
or other detected binary requires an explicit allowlist change reviewed in the
same PR. Changed paths, hashes, or unexpectedly removed files fail the job.

## Outstanding source-build work

1. **Wayland server**: two identical checked-in copies of
   `libwayland-server.so`; `waylandcomp/CMakeLists.txt` imports one prebuilt
   directly. Pin the matching Wayland source and Android-specific patch/toolchain
   inputs, build the library in CI, and stage a single resulting ELF to JNI.
   Check required symbols, SONAME, Android ABI, and compositor boot before
   removing the checked-in copies.
2. **PulseAudio / libffi / libltdl / libsndfile**: the audio recipe downloads
   PulseAudio 13.0 *sources* to build extra sink modules, but the base
   `pulseaudio.tzst` and JNI libraries are already compiled. Add explicit
   source builds for the base runtime/dependencies and verify ABI + plugin
   linkage. Avoid changing the working audio bundle until an Android smoke
   test exercises playback and suspend/resume.
3. **Innoextract / Boost**: the runtime asset task stages a checked-in AArch64
   executable and five Boost shared libraries. Build innoextract from a pinned
   upstream revision and cross-build matching dependencies, or remove the
   prebuilt tool if another source-built extraction path covers its use cases.
4. **Turnip archives**: verify their exact Mesa commits, build toolchains and
   redistribution notices. Migrate to pinned, checked source builds or
   independently verified upstream artifacts with documented provenance.

The `source_revision` value `unverified` explicitly means we have **not**
established which upstream source produced the checked-in bytes. A project
homepage and SHA-256 hash do not establish reproducibility, correspondence
to source, license compliance, or supply-chain safety. They make unresolved
items visible and prevent silent binary additions.

This gate intentionally inventories build tooling and opaque data separately
from executable runtime code. It does not scan binaries downloaded dynamically
or produced during CI: those are governed by the existing component recipes,
hash pins, artifact handoff, and release checks. The build's verified preload
snapshots are likewise covered by `tools/components/preloads.py`, not by
this tracked-file exception list.

To remove an exception, migrate the build to source and delete its tracked
artifact and manifest entry in the same PR. To update one, show the exact
upstream revision, source URL, license/notice, patch set, build command,
toolchain and validation evidence in the PR; don't just refresh the hash.
