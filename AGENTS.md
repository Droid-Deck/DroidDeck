# Rules

- Comments should be rare, factual, and explain only non-obvious external constraints or business invariants.
- Always compile APKs with the prepared Linux runtime assets and valid add-on pack checksums so a fresh installation on another phone can install the runtime. Never use `reuseInstalledRuntime` or rely on runtime files already installed on a connected device. Run APK runtime-input verification before sharing a build.
