package com.droiddeck.launcher.input;

import java.io.File;

final class FakeInputRingPaths {
    static final String RING_DIR_NAME = "fakeinput-rings";

    private FakeInputRingPaths() {}

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

    static String exportPath(File ringFile) {
        return ringFile.getAbsolutePath();
    }
}
