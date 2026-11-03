package com.droiddeck.launcher.gpu;

import android.content.Context;
import android.os.Build;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import com.droiddeck.launcher.core.FileUtils;
import com.droiddeck.launcher.core.TarZst;
import java.io.File;
import java.io.IOException;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Predicate;
import org.json.JSONObject;

/** Isolated glibc-to-Android Vulkan bridge. It does not replace the rootfs's Mesa libraries. */
public final class SystemVulkanDriver {
    private static final String ASSET = "hybris/runtime.tzst";
    /** Vendor HALs the bridge loads. Samsung Xclipse (Exynos, sgpu) is experimental. */
    private static final String[] HALS = {
        "/vendor/lib64/hw/vulkan.powervr.so",
        "/vendor/lib64/hw/vulkan.samsung.so"
    };
    private static final String[] REQUIRED = {
        "lib/libhybris-vulkan-icd.so.0", "lib/libhybris-common.so.1",
        "lib/libhybris/linker/q.so", "lib/libVkLayer_hybris_compat.so",
        "run-proton.py", "wsi/libVkLayer_window_system_integration.so",
        "wsi/VkLayer_window_system_integration.json",
        "dxvk/x32/d3d8.dll", "dxvk/x32/d3d9.dll", "dxvk/x32/d3d10core.dll",
        "dxvk/x32/d3d11.dll", "dxvk/x32/dxgi.dll",
        "dxvk/x64/d3d8.dll", "dxvk/x64/d3d9.dll", "dxvk/x64/d3d10core.dll",
        "dxvk/x64/d3d11.dll", "dxvk/x64/dxgi.dll"
    };

    /** Rootfs directory of the Xclipse layer the apk stages (SessionFiles). */
    private static final String XCLIPSE_LAYER_DIR = "/usr/local/lib/droiddeck-xclipse";

    private SystemVulkanDriver() {}

    /**
     * The first vendor HAL present, or null. An Exynos with a Mali (Exynos 1280/1380: /dev/mali0)
     * may name its HAL vulkan.samsung.so too; that is a Mali, not an Xclipse, and is not taken.
     */
    public static String hal() {
        for (String path : HALS) {
            if (!new File(path).isFile()) continue;
            if (path.endsWith("/vulkan.samsung.so") && new File("/dev/mali0").exists()) continue;
            return path;
        }
        return null;
    }

    /** Samsung's Xclipse (Exynos, sgpu) rather than PowerVR. */
    public static boolean isXclipse() {
        String hal = hal();
        return hal != null && hal.endsWith("/vulkan.samsung.so");
    }

    /** The system-driver GPU family, for labels. */
    public static String gpuName() { return isXclipse() ? "Xclipse" : "PowerVR"; }

    /** Whether a system-driver GPU (PowerVR or Xclipse) is present; the name predates Xclipse. */
    public static boolean isPowerVr() { return hal() != null; }

    /** HAL detection names the GPU; launching also needs the real DRM render node. */
    public static boolean isAvailable() { return isPowerVr() && renderNodeAvailable(); }

    public static boolean renderNodeAvailable() {
        return renderNodePath() != null;
    }

    /** Select the actual PowerVR node, including devices where another GPU owns renderD128. */
    public static String renderNodePath() {
        String[] nodes = new File("/dev/dri").list();
        if (nodes == null) nodes = new File("/sys/class/drm").list();
        return findRenderNode(nodes, SystemVulkanDriver::renderNodeAvailable);
    }

    static String findRenderNode(String[] nodes, Predicate<String> available) {
        TreeSet<String> candidates = new TreeSet<>();
        // Android can deny directory listing while allowing direct access. Probe the kernel's
        // reserved render range too; directory entries also cover dynamically allocated minors.
        for (int minor = 128; minor < 192; minor++) candidates.add("renderD" + minor);
        if (nodes != null) for (String node : nodes)
            if (node.matches("renderD[0-9]+")) candidates.add(node);
        for (String node : candidates) {
            String path = "/dev/dri/" + node;
            if (available.test(path)) return path;
        }
        return null;
    }

    private static boolean renderNodeAvailable(String node) {
        try {
            if (!renderNodeAvailable(OsConstants.S_ISCHR(Os.stat(node).st_mode),
                    Os.access(node, OsConstants.R_OK), Os.access(node, OsConstants.W_OK))) return false;
            return powerVrDevice(new File("/sys/class/drm/" + new File(node).getName() + "/device"));
        } catch (ErrnoException error) {
            return false;
        }
    }

    static boolean powerVrDriver(String driver) {
        return "pvrsrvkm".equals(driver) || "pvr".equals(driver) || "powervr".equals(driver)
                || "sgpu".equals(driver);
    }

    static boolean powerVrDevice(File device) {
        try {
            return powerVrDriver(Files.readSymbolicLink(new File(device, "driver").toPath())
                    .getFileName().toString());
        } catch (IOException error) {
            // Some Android policies expose uevent but deny reading the driver symlink.
            try (BufferedReader input = Files.newBufferedReader(
                    new File(device, "uevent").toPath(), StandardCharsets.US_ASCII)) {
                String line;
                while ((line = input.readLine()) != null)
                    if (line.startsWith("DRIVER=")) return powerVrDriver(line.substring(7));
            } catch (IOException ignored) { }
            return false;
        }
    }

    static boolean renderNodeAvailable(boolean characterDevice, boolean readable, boolean writable) {
        return characterDevice && readable && writable;
    }

    public static boolean usesDefault(String choice) {
        return usesDefault(isPowerVr(), choice);
    }

    static boolean usesDefault(boolean powerVr, String choice) {
        return powerVr && (choice == null || choice.isEmpty());
    }

    /** APK-versioned directories stay intact while an older session still has libraries mapped. */
    public static synchronized File prepare(Context context) throws IOException {
        if (!isPowerVr()) throw new IOException("System PowerVR Vulkan driver is unavailable");
        if (!renderNodeAvailable()) throw new IOException(
                "System PowerVR requires an identifiable, readable and writable DRM render node");
        File target = runtimeDirectory(context);
        File base = target.getParentFile();
        if (complete(target)) return target;
        if (!base.isDirectory() && !base.mkdirs()) throw new IOException("Cannot create system Vulkan directory");
        File staged = new File(base, ".stage-" + UUID.randomUUID());
        try {
            if (!TarZst.extractAsset(context, ASSET, staged)) throw new IOException("Cannot unpack system Vulkan runtime");
            for (String name : REQUIRED)
                if (!present(staged, name)) throw new IOException("Missing system Vulkan component: " + name);
            if (!new File(staged, "run-proton.py").canExecute())
                throw new IOException("System Vulkan Proton wrapper is not executable");
            JSONObject library = new JSONObject();
            library.put("library_path", new File(target, REQUIRED[0]).getPath());
            library.put("api_version", "1.3.0");
            JSONObject manifest = new JSONObject();
            manifest.put("file_format_version", "1.0.0");
            manifest.put("ICD", library);
            Files.write(new File(staged, "icd.json").toPath(), manifest.toString().getBytes(StandardCharsets.UTF_8));
            if (target.exists()) throw new IOException("Incomplete system Vulkan runtime directory: " + target.getName());
            if (!staged.renameTo(target)) throw new IOException("Cannot publish system Vulkan runtime");
            return target;
        } catch (org.json.JSONException error) {
            throw new IOException("Cannot write system Vulkan manifest", error);
        } finally {
            if (staged.exists()) FileUtils.delete(staged);
        }
    }

    /** The current APK's versioned runtime path, without extracting or modifying it. */
    public static File runtimeDirectory(Context context) throws IOException {
        String version;
        try (BufferedReader input = new BufferedReader(new InputStreamReader(
                context.getAssets().open("hybris/version.txt"), StandardCharsets.US_ASCII))) {
            version = input.readLine();
        }
        if (version == null || !version.matches("[0-9a-f]{64}")) throw new IOException("Invalid system Vulkan runtime version");
        return new File(new File(context.getFilesDir(), "system_vulkan"), version);
    }

    /** Collect abandoned stages, and obsolete versions once older guests can no longer load them. */
    public static synchronized void pruneUnused(Context context, boolean guestsStopped) throws IOException {
        pruneUnused(runtimeDirectory(context), guestsStopped);
    }

    static synchronized void pruneUnused(File current, boolean guestsStopped) {
        File base = current.getParentFile();
        if (base == null || Files.isSymbolicLink(base.toPath()) || Files.isSymbolicLink(current.toPath())) return;
        File[] entries = base.listFiles();
        if (entries == null) return;
        // Retain the previous good version if publishing the replacement failed.
        boolean pruneVersions = guestsStopped && complete(current);
        for (File entry : entries) {
            if (entry.equals(current)) continue;
            String name = entry.getName();
            boolean oldVersion = pruneVersions && name.matches("[0-9a-f]{64}");
            boolean abandonedStage = name.matches("\\.stage-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
            // The prepare monitor excludes any extraction still writing a .stage directory.
            if (oldVersion || abandonedStage) FileUtils.delete(entry);
        }
    }

    private static boolean complete(File directory) {
        if (!new File(directory, "icd.json").isFile()) return false;
        for (String name : REQUIRED) if (!present(directory, name)) return false;
        return new File(directory, "run-proton.py").canExecute();
    }

    private static boolean present(File directory, String name) {
        File file = new File(directory, name);
        return file.isFile() && file.length() > 0;
    }

    public static String icdPath(Context context) {
        try {
            return new File(prepare(context), "icd.json").getPath();
        } catch (IOException error) {
            throw new IllegalStateException("System Vulkan runtime is unavailable", error);
        }
    }

    public static void addEnvironment(Context context, List<String> environment) {
        File directory;
        try { directory = prepare(context); }
        catch (IOException error) { throw new IllegalStateException("System Vulkan runtime is unavailable", error); }
        String node = renderNodePath();
        if (node == null) throw new IllegalStateException("System PowerVR render node is unavailable");
        environment.add("HYBRIS_ANDROID_SDK_VERSION=" + Build.VERSION.SDK_INT);
        environment.add("HYBRIS_VULKAN_HAL=" + hal());
        environment.add("HYBRIS_LINKER_DIR=" + new File(directory, "lib/libhybris/linker").getPath());
        // Prefer framework dependencies for the system Vulkan loader, including libbase.so.
        environment.add("HYBRIS_LD_LIBRARY_PATH=/system/lib64:/system_ext/lib64:/vendor/lib64/egl:/vendor/lib64/hw:/vendor/lib64");
        boolean xclipse = isXclipse();
        // Xclipse gets its own layer (tools/linuxfs/xclipse-layer) in front of everything: it sets
        // bionic's environ for the driver's shader compiler, among other driver workarounds. The
        // session's own clients need nothing else; games keep the compat layer's BC emulation.
        String layers = xclipse ? "VK_LAYER_DROIDDECK_xclipse" : "VK_LAYER_HYBRIS_compat";
        environment.add("VK_LAYER_PATH=" + new File(directory, "lib").getPath() + (xclipse ? ":" + XCLIPSE_LAYER_DIR : ""));
        environment.add("VK_INSTANCE_LAYERS=" + layers);
        // Proton game launches add their WSI layer in front of these (droiddeck-game-env).
        environment.add("DROIDDECK_HYBRIS_LAYERS=" + (xclipse ? layers + ":VK_LAYER_HYBRIS_compat" : layers));
        if (xclipse) environment.add("DROIDDECK_HYBRIS_LAYER_PATH=" + XCLIPSE_LAYER_DIR);
        // DXVK 3 needs 256 bytes of push constants; this driver has 128.
        if (xclipse) environment.add("DROIDDECK_HYBRIS_DXVK=dxvk-2.6.2");
        // The Android driver has a render node, but no KMS primary node. SDL presents
        // through the wrapper's Wayland Vulkan swapchain instead of a KMS-style path.
        environment.add("BL_GAMESCOPE_BACKEND=sdl");
        // The Android pvr kernel accepts Vulkan DMA-BUF imports but rejects PRIME GEM handles.
        environment.add("BL_POWERVR_DRM_HANDLES=1");
        environment.add("BL_POWERVR_RENDER_NODE=" + node);
        // Inner Gamescope/Xwayland lacks the bridge's Android-buffer WSI protocols.
        environment.add("BL_STEAM_GL_PRESENT=1");
        // Only actual Proton game launches use the bundled DXVK and X11 WSI layer.
        environment.add("DROIDDECK_POWERVR_RUNTIME=" + directory.getPath());
    }
}
