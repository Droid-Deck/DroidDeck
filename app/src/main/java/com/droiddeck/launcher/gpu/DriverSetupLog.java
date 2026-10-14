package com.droiddeck.launcher.gpu;

import android.content.Context;
import android.util.Log;
import java.io.File;
import java.io.FileWriter;

public final class DriverSetupLog {
    private DriverSetupLog() {}

    public static synchronized void write(Context context, String stage) {
        Log.i("DriverSetup", stage);
        append(context.getFilesDir(), stage);
        try { append(context.getExternalFilesDir(null), stage); }
        catch (Exception ignored) {   }
    }

    private static void append(File directory, String stage) {
        if (directory == null) return;
        try {
            directory.mkdirs();
            File file = new File(directory, "driver-setup.txt");
            try (FileWriter out = new FileWriter(file, file.length() < 131072)) {
                out.write(System.currentTimeMillis() + " " + stage + "\n");
            }
        } catch (Exception ignored) {   }
    }
}
