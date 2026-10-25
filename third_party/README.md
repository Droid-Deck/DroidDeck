# Controller source snapshots

`sdlbridge/upstream` contains the reusable Java and native controller implementation
by pablo89898991. `sdl3/upstream` contains SDL's Android Java bindings. Each snapshot's
`upstream.json` records its exact upstream revision or release archive and the
SHA-256 of every imported file. License notices are included alongside the snapshots.

Keep snapshots unchanged. DroidDeck's embedded-mode adjustments live in each
component's `patches` directory. `prepareControllerSources` verifies the snapshots
and applies those patches into `app/build/controller-sources`; Kotlin, Java and CMake
compile against that generated directory. No SdlBridge download is needed to build.
SDL's native release archive is separately pinned and hash-checked by CMake.

DroidDeck's Compose UI, session lifecycle, controller slots and Linux input integration
remain in the application's normal source directories.

To update a dependency, import the selected upstream files, update the revision and
file hashes in `upstream.json`, and rebase the small local patch set. Run
`python3 tools/prepare_controller_sources.py` to check the inventory, hashes and patch
application before building. An upstream change that breaks a patch fails preparation.
