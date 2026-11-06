package com.droiddeck.launcher.gpu;

import android.content.Context;
import android.util.Log;

import com.droiddeck.launcher.R;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;

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
        if (value == null || value.isEmpty()) return SystemVulkanDriver.isPowerVr()
                ? context.getString(R.string.gpu_system_powervr) : "Runtime default (built into the runtime)";
        LinuxVulkanDriverManager m = new LinuxVulkanDriverManager(context);
        if (!m.isInstalled(value)) return value + (SystemVulkanDriver.isPowerVr()
                ? " (imported, missing)" : " (imported, missing - uses the runtime default)");
        String ver = m.getDriverVersion(value);
        return m.getDriverName(value) + (ver.isEmpty() ? "" : " " + ver) + " (imported)";
    }

    public static String resolveIcdPath(Context context, String value) {
        boolean powerVr = SystemVulkanDriver.isPowerVr();
        return resolveIcdPath(value, powerVr,
                () -> SystemVulkanDriver.icdPath(context), id -> {
                    String icd = new LinuxVulkanDriverManager(context).getIcdPath(id);
                    if (icd == null) Log.w(TAG, "imported Linux driver \"" + id + "\" is gone"
                            + (powerVr ? "" : "; using the runtime's own driver"));
                    else Log.i(TAG, "Linux session draws with imported driver " + id + " (" + icd + ")");
                    return icd;
                });
    }

    /** Resolve the selected backend before looking up an imported driver. */
    static String resolveIcdPath(String value, boolean powerVr, Supplier<String> systemIcd,
                                 Function<String, String> importedIcd) {
        if (SystemVulkanDriver.usesDefault(powerVr, value)) return systemIcd.get();
        if (value == null || value.isEmpty()) return null;
        String icd = importedIcd.apply(value);
        if (powerVr && icd == null) throw new IllegalStateException(
                "Selected Linux Vulkan driver \"" + value + "\" is unavailable. Select an installed driver or use Auto.");
        return icd;
    }
}
