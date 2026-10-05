package com.droiddeck.launcher.input;

import java.io.File;

/**
 * Where the fake input rings live and how the guest is told about them. Kept apart from
 * {@link FakeInputWriter}, which loads native code, so it can be tested on the JVM.
 */
final class FakeInputRingPaths {
    static final String RING_DIR_NAME = "fakeinput-rings";

    private FakeInputRingPaths() {}

    /** The rings sit beside the fake evdev nodes: session/dev/input -> session/dev/fakeinput-rings. */
    static File ringDir(File fakeInputDir) {
        if (fakeInputDir == null) {
            return null;
        }
        File inputDir = fakeInputDir.getAbsoluteFile();
        File parent = inputDir.getParentFile();
        return new File(parent != null ? parent : inputDir, RING_DIR_NAME);
    }

    static File ringFile(File fakeInputDir, int slot) {
        File ringDir = ringDir(fakeInputDir);
        return ringDir != null ? new File(ringDir, "ring" + slot) : null;
    }

    /**
     * The ring's path as the guest opens it. The session tree is bound into the guest at its own
     * host path, spelled the way {@code Context.getFilesDir()} spells it (LinuxRuntime.binds), so
     * the export has to use that same spelling. A canonical path is not that: where
     * /data/user/0 is a link, or a separate mount, onto /data/data (ColorOS 16, #166) it resolves
     * to a prefix nothing is bound at, and every pad, physical and on-screen, goes dead.
     */
    static String exportPath(File ringFile) {
        return ringFile.getAbsolutePath();
    }
}
