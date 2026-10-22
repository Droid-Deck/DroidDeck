package com.droiddeck.launcher.gpu;

import android.content.Context;
import android.net.Uri;
import android.os.Environment;
import android.util.Log;

import com.droiddeck.launcher.R;
import com.droiddeck.launcher.core.FileUtils;
import com.droiddeck.launcher.core.TarZst;
import com.droiddeck.launcher.session.QuestExperimental;
import com.droiddeck.launcher.session.SessionPrefs;

import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public final class TurnipDriver {
    private static final String TAG = "TurnipDriver";
    private static final String DRIVER_A6XX = "turnip26.3.0-a6xx";
    private static final String DRIVER_A7XX = "turnip25.1.0";
    private static final String DRIVER_A8XX = "turnip-sdk36";
    public static final List<String> BUNDLED = Collections.unmodifiableList(Arrays.asList(
            DRIVER_A7XX, DRIVER_A8XX, com.droiddeck.launcher.session.QuestExperimental.DRIVER,
            DRIVER_A6XX, com.droiddeck.launcher.session.QuestExperimental.DRIVER_A6XX));
    private static final String OVERRIDE_FILE = "Download/droiddeck-driver";
    private static final String PREFS = "graphics_driver";
    private static final String KEY_HIDDEN = "hiddenBundled";
    public static final String AUTO = "";

    public static final String HELP_TEXT =
            "The driver the app's compositor puts frames on the screen with - the last step of every "
            + "session, Steam or desktop. AdrenoTools zips only (vulkan.adXXXX.so): a \"-Linux\" Turnip "
            + "belongs in the Linux runtime list above and is refused here. The compositor loads its "
            + "driver once per app process, so a change takes effect after the app is fully closed and "
            + "started again.";

    private final Context context;
    private final File contentDir;

    public TurnipDriver(Context context) {
        this.context = context;
        this.contentDir = new File(context.getFilesDir(), "graphics_driver");
    }

    public String driverPath(String driverId) {
        return new File(contentDir, driverId).getAbsolutePath() + "/";
    }

    public String libraryName(String driverId) {
        JSONObject meta = readMeta(driverId);
        return meta != null ? meta.optString("libraryName", null) : null;
    }

    public String displayName(String driverId) {
        JSONObject meta = readMeta(driverId);
        String name = meta != null ? meta.optString("name", "") : "";
        return name.isEmpty() ? driverId : name;
    }

    public String driverVersion(String driverId) {
        JSONObject meta = readMeta(driverId);
        return meta != null ? meta.optString("driverVersion", "") : "";
    }

    private JSONObject readMeta(String driverId) {
        if (driverId == null || driverId.isEmpty() || driverId.contains("/") || driverId.contains("..")) return null;
        File meta = new File(new File(contentDir, driverId), "meta.json");
        String content = FileUtils.readString(meta);
        if (content == null) return null;
        try {
            return new JSONObject(content);
        } catch (Exception e) {
            Log.w(TAG, "meta.json for " + driverId, e);
            return null;
        }
    }

    public boolean isInstalled(String driverId) {
        String library = libraryName(driverId);
        return library != null && !library.isEmpty() && new File(new File(contentDir, driverId), library).isFile();
    }

    public List<String> visibleBundled() {
        java.util.Set<String> hidden = hiddenBundled();
        ArrayList<String> out = new ArrayList<>();
        for (String id : BUNDLED) if (!hidden.contains(id)) out.add(id);
        return out;
    }

    public java.util.Set<String> hiddenBundled() {
        return new java.util.HashSet<>(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getStringSet(KEY_HIDDEN, java.util.Collections.<String>emptySet()));
    }

    public void hideBundled(String driverId) {
        if (!BUNDLED.contains(driverId)) return;
        FileUtils.delete(new File(contentDir, driverId));
        java.util.Set<String> hidden = hiddenBundled();
        hidden.add(driverId);
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putStringSet(KEY_HIDDEN, hidden).apply();
        Log.i(TAG, "bundled driver " + driverId + " hidden by the user");
    }

    public void restoreBundled() {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_HIDDEN).apply();
    }

    public List<String> enumerateImported() {
        ArrayList<String> ids = new ArrayList<>();
        File[] dirs = contentDir.listFiles();
        if (dirs == null) return ids;
        for (File d : dirs) {
            String id = d.getName();
            if (d.isDirectory() && !BUNDLED.contains(id) && !id.startsWith(".") && isInstalled(id)) ids.add(id);
        }
        Collections.sort(ids);
        return ids;
    }

    public String autoId() {
        String id = chooseByDevice();
        return id == null ? "system" : id;
    }

    public void remove(String driverId) {
        if (driverId == null || driverId.isEmpty() || BUNDLED.contains(driverId)
                || driverId.contains("/") || driverId.contains("..")) return;
        Log.d(TAG, "removing imported driver " + driverId);
        FileUtils.delete(new File(contentDir, driverId));
        if (driverId.equals(SessionPrefs.androidDriver(context))) SessionPrefs.setAndroidDriver(context, AUTO);
    }

    public String installFromZip(Uri zipUri, String displayName) throws IOException {
        File tmpDir = newStagingDir();
        boolean keep = false;
        try {
            try (InputStream is = context.getContentResolver().openInputStream(zipUri);
                 ZipInputStream zis = new ZipInputStream(is)) {
                if (is == null) throw new IOException("cannot open " + zipUri);
                ZipEntry entry;
                while ((entry = zis.getNextEntry()) != null) {
                    if (entry.isDirectory()) continue;
                    String base = new File(entry.getName()).getName();
                    if (base.isEmpty()) continue;
                    Files.copy(zis, new File(tmpDir, base).toPath(), StandardCopyOption.REPLACE_EXISTING);
                }
            }
            String id = adopt(tmpDir, displayName);
            keep = true;
            return id;
        } finally {
            if (!keep) FileUtils.delete(tmpDir);
        }
    }

    File newStagingDir() throws IOException {
        if (!contentDir.exists()) contentDir.mkdirs();
        File tmpDir = new File(contentDir, ".tmp-" + System.nanoTime());
        FileUtils.delete(tmpDir);
        if (!tmpDir.mkdirs()) throw new IOException("cannot create " + tmpDir);
        return tmpDir;
    }

    String adopt(File tmpDir, String displayName) throws IOException {
        String rejected = rejectionReason(tmpDir);
        if (rejected != null) throw new IllegalArgumentException(context.getString(R.string.turnip_not_adrenotools, rejected));

        JSONObject meta = readMetaFile(new File(tmpDir, "meta.json"));
        String name = meta != null ? meta.optString("name", "") : "";
        if (name.isEmpty()) {
            name = displayName != null ? displayName : "adrenotools-driver";
            if (name.toLowerCase().endsWith(".zip")) name = name.substring(0, name.length() - 4);
        }
        String id = uniqueId(LinuxVulkanDriverManager.sanitizeId(name));
        File dir = new File(contentDir, id);
        if (!tmpDir.renameTo(dir)) throw new IOException("cannot move into " + dir);
        Log.i(TAG, "imported AdrenoTools driver " + id + " (" + (meta != null ? meta.optString("libraryName", "") : "?") + ") -> " + dir);
        return id;
    }

    private static JSONObject readMetaFile(File metaFile) {
        try {
            return new JSONObject(FileUtils.readString(metaFile));
        } catch (Exception e) {
            return null;
        }
    }

    private String rejectionReason(File dir) {
        File metaFile = new File(dir, "meta.json");
        if (!metaFile.isFile()) return context.getString(R.string.turnip_reject_no_meta);
        String libraryName, kind;
        try {
            JSONObject meta = new JSONObject(FileUtils.readString(metaFile));
            libraryName = meta.optString("libraryName", "");
            kind = meta.optString("kind", "");
        } catch (Exception e) {
            return context.getString(R.string.turnip_reject_meta_unreadable, e.getMessage());
        }
        if ("linux-vulkan-icd".equals(kind))
            return context.getString(R.string.turnip_reject_linux_driver);
        if ("wayland-game-driver".equals(kind))
            return context.getString(R.string.turnip_reject_wayland);
        if (libraryName.isEmpty())
            return context.getString(R.string.turnip_reject_no_library_name);
        File library = new File(dir, libraryName);
        if (!library.isFile())
            return context.getString(R.string.turnip_reject_library_missing, libraryName);
        if (!LinuxVulkanDriverManager.isAarch64Elf(library))
            return context.getString(R.string.turnip_reject_not_aarch64, libraryName);
        if (LinuxVulkanDriverManager.containsAscii(library, "libc.so.6"))
            return context.getString(R.string.turnip_reject_glibc, libraryName);
        return null;
    }

    private String uniqueId(String base) {
        String id = base;
        int n = 2;
        while (new File(contentDir, id).exists()) id = base + "-" + (n++);
        return id;
    }

    public String install() {
        String id = choose();
        DriverSetupLog.write(context, "install.begin driver=" + id);
        if (id == null) return null;
        File dir = new File(contentDir, id);
        if (!new File(dir, "meta.json").isFile()) {
            FileUtils.delete(dir);
            if (!TarZst.extractAsset(context, "graphics_driver/adrenotools-" + id + ".tzst", dir)) {
                Log.e(TAG, "could not unpack " + id);
                DriverSetupLog.write(context, "install.unpack-failed driver=" + id);
                FileUtils.delete(dir);
                return null;
            }
        }
        String library = libraryName(id);
        if (library == null || !new File(dir, library).isFile()) {
            Log.e(TAG, id + " unpacked without " + library);
            return null;
        }
        Log.i(TAG, "graphics driver " + id + " (" + library + ")");
        DriverSetupLog.write(context, "install.ready driver=" + id + " library=" + library);
        return id;
    }

    private String choose() {
        String chosen = SessionPrefs.androidDriver(context);
        if (chosen != null && !chosen.isEmpty()) {
            if (BUNDLED.contains(chosen)) {
                Log.i(TAG, "driver pinned by the user: " + chosen);
                return chosen;
            }
            if (isInstalled(chosen)) {
                Log.i(TAG, "imported driver chosen by the user: " + chosen);
                return chosen;
            }
            Log.w(TAG, "chosen driver \"" + chosen + "\" is gone; picking by GPU");
        }
        return chooseByDevice();
    }

    private String chooseByDevice() {
        File override = new File(Environment.getExternalStorageDirectory(), OVERRIDE_FILE);
        String forced = override.isFile() ? FileUtils.readString(override) : null;
        if (forced != null) {
            forced = forced.trim().toLowerCase(java.util.Locale.US);
            Log.i(TAG, "driver forced by " + override + ": " + forced);
            if (forced.startsWith("system")) return null;
            if (forced.startsWith("a8")) return DRIVER_A8XX;
            if (forced.startsWith("a7")) return DRIVER_A7XX;
            if (forced.startsWith("a6")) return DRIVER_A6XX;
        }
        String quest = QuestExperimental.INSTANCE.defaultAndroidDriver();
        if (quest != null) return quest;
        int model = GpuInfo.Companion.detect().getModel();
        Log.i(TAG, "gpu model: " + (model == 0 ? "unknown" : model));
        return chooseByModel(model, android.os.Build.VERSION.SDK_INT);
    }

    static String chooseByModel(int model, int sdk) {
        if (model >= 800) return DRIVER_A8XX;
        if (model >= 700) return DRIVER_A7XX;
        if (model >= 600) return DRIVER_A6XX;
        return sdk >= 36 ? DRIVER_A8XX : DRIVER_A7XX;
    }
}
