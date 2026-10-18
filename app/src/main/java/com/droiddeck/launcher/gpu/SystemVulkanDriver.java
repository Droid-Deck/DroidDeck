package com.droiddeck.launcher.gpu;

import android.content.Context;
import android.os.Build;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import com.droiddeck.launcher.core.FileUtils;
import java.io.File;
import java.io.IOException;
import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Predicate;

/** Isolated glibc-to-Android Vulkan bridge. It does not replace the rootfs's Mesa libraries. */
public final class SystemVulkanDriver {
    /** Vendor policy is independent from the shared libhybris HAL loader and Vulkan layer. */
    public enum Profile {
        POWERVR("powervr", "PowerVR", "/vendor/lib64/hw/vulkan.powervr.so", true,
                "dxvk", "native", true, "VK_LAYER_HYBRIS_compat", "VK_LAYER_HYBRIS_compat", null, null,
                new String[] {"pvrsrvkm", "pvr", "powervr"}),
        XCLIPSE("xclipse", "Xclipse", "/vendor/lib64/hw/vulkan.samsung.so", true,
                "dxvk-2.6.2", "native", false, "VK_LAYER_HYBRIS_compat", "VK_LAYER_HYBRIS_compat", null, "0",
                new String[] {"sgpu"}),
        MALI("mali", "Mali", "/vendor/lib64/hw/vulkan.mali.so", false,
                "dxvk-mali", "x11-shm", false, "VK_LAYER_HYBRIS_compat", "VK_LAYER_HYBRIS_compat", null, null,
                new String[] {});

        public final String id;
        public final String gpuName;
        public final String halPath;
        public final boolean requiresRenderNode;
        public final String dxvkPayload;
        public final String presentation;
        public final boolean softwareSteamCef;
        final String sessionLayers;
        final String gameLayers;
        final String extraLayerPath;
        final String sessionBcTextures;
        private final String[] drmDrivers;

        Profile(String id, String gpuName, String halPath, boolean requiresRenderNode,
                String dxvkPayload, String presentation, boolean softwareSteamCef, String sessionLayers,
                String gameLayers, String extraLayerPath, String sessionBcTextures, String[] drmDrivers) {
            this.id = id;
            this.gpuName = gpuName;
            this.halPath = halPath;
            this.requiresRenderNode = requiresRenderNode;
            this.dxvkPayload = dxvkPayload;
            this.presentation = presentation;
            this.softwareSteamCef = softwareSteamCef;
            this.sessionLayers = sessionLayers;
            this.gameLayers = gameLayers;
            this.extraLayerPath = extraLayerPath;
            this.sessionBcTextures = sessionBcTextures;
            this.drmDrivers = drmDrivers;
        }

        public static Profile fromHal(String hal) {
            for (Profile profile : values()) if (profile.halPath.equals(hal)) return profile;
            return null;
        }
    }

    private static final String[] REQUIRED = {
        "lib/libhybris-vulkan-icd.so.0", "lib/libhybris-common.so.1",
        "lib/libhybris/linker/q.so", "lib/libVkLayer_hybris_compat.so",
        "lib/libVkLayer_DROIDDECK_placed_memory.so", "lib/VkLayer_DROIDDECK_placed_memory.json",
        "run-proton.py", "wsi/libVkLayer_window_system_integration.so",
        "wsi/VkLayer_window_system_integration.json",
        "dxvk/x32/d3d8.dll", "dxvk/x32/d3d9.dll", "dxvk/x32/d3d10core.dll",
        "dxvk/x32/d3d11.dll", "dxvk/x32/dxgi.dll",
        "dxvk/x64/d3d8.dll", "dxvk/x64/d3d9.dll", "dxvk/x64/d3d10core.dll",
        "dxvk/x64/d3d11.dll", "dxvk/x64/dxgi.dll",
        "dxvk-mali/i386-windows/d3d8.dll", "dxvk-mali/i386-windows/d3d9.dll", "dxvk-mali/i386-windows/d3d10core.dll", "dxvk-mali/i386-windows/d3d11.dll", "dxvk-mali/i386-windows/dxgi.dll",
        "dxvk-mali/x86_64-windows/d3d8.dll", "dxvk-mali/x86_64-windows/d3d9.dll", "dxvk-mali/x86_64-windows/d3d10core.dll", "dxvk-mali/x86_64-windows/d3d11.dll", "dxvk-mali/x86_64-windows/dxgi.dll",
        "dxvk-mali/aarch64-windows/d3d8.dll", "dxvk-mali/aarch64-windows/d3d9.dll", "dxvk-mali/aarch64-windows/d3d10core.dll", "dxvk-mali/aarch64-windows/d3d11.dll", "dxvk-mali/aarch64-windows/dxgi.dll"
    };

    private SystemVulkanDriver() {}

    public static Profile profile() {
        return findProfile(path -> new File(path).isFile());
    }

    /** An ambiguous HAL installation must not accidentally activate another vendor's quirks. */
    static Profile findProfile(Predicate<String> present) {
        Profile selected = null;
        for (Profile profile : Profile.values()) {
            if (!present.test(profile.halPath)) continue;
            if (selected != null) return null;
            selected = profile;
        }
        return selected;
    }

    public static String hal() {
        Profile selected = profile();
        return selected == null ? null : selected.halPath;
    }

    public static String systemHalPath() { return hal(); }
    public static boolean isPowerVr() { return profile() == Profile.POWERVR; }
    public static boolean isXclipse() { return profile() == Profile.XCLIPSE; }
    public static boolean isMali() { return profile() == Profile.MALI; }
    public static boolean isSupportedGpu() { return profile() != null; }

    public static String gpuName() {
        Profile selected = profile();
        return selected == null ? "Vulkan" : selected.gpuName;
    }

    public static boolean requiresRenderNode() {
        Profile selected = profile();
        return selected != null && selected.requiresRenderNode;
    }

    public static boolean isAvailable() {
        Profile selected = profile();
        return selected != null && (!selected.requiresRenderNode || renderNodeAvailable());
    }

    public static boolean renderNodeAvailable() {
        return renderNodePath() != null;
    }

    /** Select this HAL profile's GPU node, skipping display controllers and other vendors. */
    public static String renderNodePath() {
        Profile selected = profile();
        if (selected == null || !selected.requiresRenderNode) return null;
        String[] nodes = new File("/dev/dri").list();
        if (nodes == null) nodes = new File("/sys/class/drm").list();
        return findRenderNode(nodes, node -> renderNodeAvailable(node, selected));
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

    private static boolean renderNodeAvailable(String node, Profile selected) {
        try {
            if (!renderNodeAvailable(OsConstants.S_ISCHR(Os.stat(node).st_mode),
                    Os.access(node, OsConstants.R_OK), Os.access(node, OsConstants.W_OK))) return false;
            return renderNodeDevice(new File("/sys/class/drm/" + new File(node).getName() + "/device"), selected);
        } catch (ErrnoException error) {
            return false;
        }
    }

    static boolean renderNodeDriver(Profile profile, String driver) {
        if (profile == null) return false;
        for (String name : profile.drmDrivers) if (name.equals(driver)) return true;
        return false;
    }

    static boolean renderNodeDevice(File device, Profile selected) {
        try {
            return renderNodeDriver(selected, Files.readSymbolicLink(new File(device, "driver").toPath())
                    .getFileName().toString());
        } catch (IOException error) {
            // Some Android policies expose uevent but deny reading the driver symlink.
            try (BufferedReader input = Files.newBufferedReader(
                    new File(device, "uevent").toPath(), StandardCharsets.US_ASCII)) {
                String line;
                while ((line = input.readLine()) != null)
                    if (line.startsWith("DRIVER=")) return renderNodeDriver(selected, line.substring(7));
            } catch (IOException ignored) { }
            return false;
        }
    }

    static boolean renderNodeAvailable(boolean characterDevice, boolean readable, boolean writable) {
        return characterDevice && readable && writable;
    }

    public static boolean usesDefault(String choice) {
        return usesDefault(isSupportedGpu(), choice);
    }

    public static boolean managesDxvk(String choice) {
        Profile selected = profile();
        return usesDefault(selected != null && selected.dxvkPayload != null, choice);
    }

    static boolean usesDefault(boolean systemGpu, String choice) {
        return systemGpu && (choice == null || choice.isEmpty());
    }

    /** Capture the active immutable component for callers that only need its directory. */
    public static synchronized File prepare(Context context) throws IOException {
        return prepare(context, false);
    }

    /**
     * Prepare the current runtime, replacing an interrupted current-version install only after
     * the session layer has established that no app-owned guest can still have it mapped.
     */
    public static synchronized File prepare(Context context, boolean guestsStopped) throws IOException {
        return prepareSnapshot(context, guestsStopped).getDirectory();
    }

    public static synchronized SystemVulkanComponentStore.Snapshot prepareSnapshot(
            Context context, boolean guestsStopped) throws IOException {
        Profile selected = profile();
        if (selected == null) throw new IOException("System Vulkan driver is unavailable");
        if (selected.requiresRenderNode && !renderNodeAvailable()) throw new IOException(
                "System " + selected.gpuName + " requires an identifiable, readable and writable DRM render node");
        return SystemVulkanComponentStore.capture(context, selected, guestsStopped);
    }

    /** Atomically move an interrupted managed install aside without traversing its contents. */
    static File quarantineIncompleteTarget(File target, boolean guestsStopped) throws IOException {
        File base = target.getParentFile();
        if (!managedRuntimePath(target) || Files.isSymbolicLink(base.toPath())
                || Files.isSymbolicLink(target.toPath()))
            throw new IOException("Unsafe system Vulkan runtime path");
        if (!target.exists()) return null;
        if (complete(target)) return null;
        if (!guestsStopped)
            throw new IOException("Incomplete system Vulkan runtime is still in use: " + target.getName());
        File quarantined = new File(base, ".stage-" + UUID.randomUUID());
        if (!target.renameTo(quarantined))
            throw new IOException("Cannot quarantine incomplete system Vulkan runtime: " + target.getName());
        return quarantined;
    }

    /** The active component path. Kept for callers migrating from the APK-backed runtime. */
    public static File runtimeDirectory(Context context) throws IOException {
        return prepare(context);
    }

    /** Collect abandoned stages, and obsolete versions once older guests can no longer load them. */
    public static synchronized void pruneUnused(Context context, boolean guestsStopped) throws IOException {
        Profile selected = profile();
        if (selected != null) SystemVulkanComponentStore.prune(context, selected, guestsStopped);
    }

    static synchronized void pruneUnused(File current, boolean guestsStopped) {
        File base = current.getParentFile();
        if (!managedRuntimePath(current) || Files.isSymbolicLink(base.toPath())
                || Files.isSymbolicLink(current.toPath())) return;
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

    private static boolean managedRuntimePath(File current) {
        File base = current.getParentFile();
        if (base == null || !"system_vulkan".equals(base.getName())
                || !current.getName().matches("[0-9a-f]{64}")) return false;
        Path basePath = base.toPath().toAbsolutePath().normalize();
        Path currentPath = current.toPath().toAbsolutePath().normalize();
        return basePath.equals(currentPath.getParent());
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
        SystemVulkanComponentStore.Snapshot snapshot;
        try { snapshot = prepareSnapshot(context, false); }
        catch (IOException error) { throw new IllegalStateException("System Vulkan runtime is unavailable", error); }
        addEnvironment(snapshot, environment);
    }

    public static String icdPath(SystemVulkanComponentStore.Snapshot snapshot) {
        return new File(snapshot.getDirectory(), "icd.json").getPath();
    }

    public static void addEnvironment(SystemVulkanComponentStore.Snapshot snapshot, List<String> environment) {
        Profile selected = profile();
        if (selected == null || !selected.id.equals(snapshot.getVariant()))
            throw new IllegalStateException("System Vulkan component does not match this GPU");
        addEnvironment(snapshot.getDirectory(), selected.halPath, selected.requiresRenderNode ? renderNodePath() : null,
                Build.VERSION.SDK_INT, environment);
    }

    public static boolean isReady(Context context) {
        Profile selected = profile();
        return selected != null && isAvailable() && SystemVulkanComponentStore.isReady(context, selected);
    }

    static void addEnvironment(File directory, String hal, String node, int sdk, List<String> environment) {
        Profile selected = Profile.fromHal(hal);
        if (selected == null) throw new IllegalStateException("System Vulkan driver is unavailable");
        if (selected.requiresRenderNode && node == null)
            throw new IllegalStateException("System " + selected.gpuName + " render node is unavailable");
        environment.add("HYBRIS_ANDROID_SDK_VERSION=" + sdk);
        environment.add("HYBRIS_VULKAN_HAL=" + selected.halPath);
        environment.add("HYBRIS_LINKER_DIR=" + new File(directory, "lib/libhybris/linker").getPath());
        // Prefer framework dependencies for the system Vulkan loader, including libbase.so.
        environment.add("HYBRIS_LD_LIBRARY_PATH=/system/lib64:/system_ext/lib64:/vendor/lib64/egl:/vendor/lib64/hw:/vendor/lib64");
        String layerPath = new File(directory, "lib").getPath();
        if (selected.extraLayerPath != null) layerPath += ":" + selected.extraLayerPath;
        environment.add("VK_LAYER_PATH=" + layerPath);
        environment.add("VK_INSTANCE_LAYERS=" + selected.sessionLayers);
        environment.add("DROIDDECK_HYBRIS_SESSION_LAYERS=" + selected.sessionLayers);
        environment.add("DROIDDECK_HYBRIS_LAYERS=" + selected.gameLayers);
        if (selected.sessionBcTextures != null) {
            // The shared layer replaces Xclipse's vendor-only outer layer. Enable BC
            // emulation only for managed games; nested compositors restore this policy.
            environment.add("HYBRIS_BC_TEXTURES=" + selected.sessionBcTextures);
            environment.add("DROIDDECK_HYBRIS_SESSION_BC_TEXTURES=" + selected.sessionBcTextures);
        }
        if (selected.extraLayerPath != null)
            environment.add("DROIDDECK_HYBRIS_LAYER_PATH=" + selected.extraLayerPath);
        if (selected.dxvkPayload != null)
            environment.add("DROIDDECK_HYBRIS_DXVK=" + selected.dxvkPayload);
        if (selected == Profile.MALI)
            environment.add("DXVK_MALI_COMPAT=1");
        // Rapid Steam Library scrolling has locked up the PowerVR CEF GPU client.
        // This changes the Steam UI only; game presentation and DXVK stay independent.
        if (selected.softwareSteamCef)
            environment.add("BL_STEAM_CEF_SOFTWARE=1");
        // The outer compositor always uses Android-buffer Wayland presentation.
        environment.add("BL_GAMESCOPE_BACKEND=sdl");
        if (selected.requiresRenderNode) {
            // The DRM preload confirms the pvr driver before emulating handles. Both render-node
            // profiles use the managed WSI layer for Steam instead of the broken plain DRI3 path.
            environment.add("BL_POWERVR_DRM_HANDLES=1");
            environment.add("BL_POWERVR_RENDER_NODE=" + node);
            environment.add("BL_STEAM_GL_PRESENT=1");
            environment.add("BL_STEAM_WSI_LAYER_PATH=" + new File(directory, "wsi").getPath());
        }
        environment.add("DROIDDECK_SYSTEM_VULKAN_GPU=" + selected.id);
        environment.add("DROIDDECK_SYSTEM_VULKAN_RUNTIME=" + directory.getPath());
        environment.add("DROIDDECK_SYSTEM_VULKAN_PRESENTATION=" + selected.presentation);
        environment.add("DROIDDECK_SYSTEM_VULKAN_DXVK=" + (selected.dxvkPayload == null ? "user" : "managed"));
        // Keep the legacy alias limited to its original DRM profiles.
        if (selected.requiresRenderNode)
            environment.add("DROIDDECK_POWERVR_RUNTIME=" + directory.getPath());
    }
}
