package com.droiddeck.launcher.gpu;

import android.content.Context;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

public final class LinuxVulkanDriver {
    private static final String TAG = "LinuxVulkanDriver";
    public static final String ENV = "BL_VK_DRIVER";

    public static final String HELP_TEXT =
            "The driver the Steam client, the games it launches and the desktop's programs render on, "
            + "inside the Linux runtime. Imported \"-Linux\" Turnip zips only: these are Linux processes "
            + "and cannot load an Android or Wayland driver. Frames still reach the screen through the "
            + "display driver below. Takes effect at the next session start.";

    private LinuxVulkanDriver() {}

    public static List<String> optionValues(Context context) {
        ArrayList<String> values = new ArrayList<>();
        values.add("");
        values.addAll(new LinuxVulkanDriverManager(context).enumerateInstalledDrivers());
        return values;
    }

    public static String optionLabel(Context context, String value) {
        if (value == null || value.isEmpty()) return "Runtime default (built into the runtime)";
        LinuxVulkanDriverManager m = new LinuxVulkanDriverManager(context);
        if (!m.isInstalled(value)) return value + " (imported, missing - uses the runtime default)";
        String ver = m.getDriverVersion(value);
        return m.getDriverName(value) + (ver.isEmpty() ? "" : " " + ver) + " (imported)";
    }

    public static String resolveIcdPath(Context context, String value) {
        if (value == null || value.isEmpty()) return null;
        LinuxVulkanDriverManager m = new LinuxVulkanDriverManager(context);
        String icd = m.getIcdPath(value);
        if (icd == null) {
            Log.w(TAG, "imported Linux driver \"" + value + "\" is gone; using the runtime's own driver");
            return null;
        }
        Log.i(TAG, "Linux session draws with imported driver " + value + " (" + icd + ")");
        return icd;
    }
}
