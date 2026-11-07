package com.droiddeck.launcher.gpu;

import android.content.Context;
import android.os.Build;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import androidx.annotation.VisibleForTesting;
import com.droiddeck.launcher.core.AddonPack;
import com.droiddeck.launcher.core.FileUtils;
import com.droiddeck.launcher.core.TarZst;
import java.io.File;
import java.io.FileInputStream;
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
    public enum Profile {
        POWERVR("powervr", "PowerVR", "/vendor/lib64/hw/vulkan.powervr.so", true, "dxvk",
                "native", "VK_LAYER_HYBRIS_compat", "VK_LAYER_HYBRIS_compat", null, null,
                new String[] {"pvrsrvkm", "pvr", "powervr"}),
        XCLIPSE("xclipse", "Xclipse", "/vendor/lib64/hw/vulkan.samsung.so", true, "dxvk-2.6.2",
                "native", "VK_LAYER_DROIDDECK_xclipse",
                "VK_LAYER_DROIDDECK_xclipse:VK_LAYER_HYBRIS_compat", XCLIPSE_LAYER_DIR, "0",
                new String[] {"sgpu"}),
        // Kept only to read existing component metadata; Mali sessions use Venus, bridge, or open drivers.
        MALI("mali", "Mali", "/vendor/lib64/hw/vulkan.mali.so", false, "dxvk-mali",
                "x11-shm", "VK_LAYER_HYBRIS_compat", "VK_LAYER_HYBRIS_compat", null, null,
                new String[] {});

        public final String id;
        public final String gpuName;
        public final String halPath;
        public final boolean systemSupported;
        public final String dxvkPayload;
        public final String presentation;
        final String sessionLayers;
        final String gameLayers;
        final String extraLayerPath;
        final String sessionBcTextures;
        private final String[] drmDrivers;

        Profile(String id, String gpuName, String halPath, boolean systemSupported,
                String dxvkPayload, String presentation,
                String sessionLayers, String gameLayers, String extraLayerPath,
                String sessionBcTextures, String[] drmDrivers) {
            this.id = id;
            this.gpuName = gpuName;
            this.halPath = halPath;
            this.systemSupported = systemSupported;
            this.dxvkPayload = dxvkPayload;
            this.presentation = presentation;
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

    public enum Provider { ADDON_PACK, MANAGED_COMPONENT }

    public static final class RuntimeLease {
        private final Provider provider;
        private final Profile profile;
        private final String version;
        private final File directory;
        private final String renderNode;
        private final SystemVulkanComponentStore.Snapshot snapshot;

        RuntimeLease(Provider provider, Profile profile, String version, File directory,
                String renderNode, SystemVulkanComponentStore.Snapshot snapshot) {
            this.provider = provider;
            this.profile = profile;
            this.version = version;
            this.directory = directory;
            this.renderNode = renderNode;
            this.snapshot = snapshot;
        }

        public Provider getProvider() { return provider; }
        public Profile getProfile() { return profile; }
        public String getVersion() { return version; }
        public File getDirectory() { return directory; }
        public String getRenderNode() { return renderNode; }
        public SystemVulkanComponentStore.Snapshot getSnapshot() { return snapshot; }
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
        "dxvk/x64/d3d11.dll", "dxvk/x64/dxgi.dll"
    };

    /** Rootfs directory of the Xclipse layer the apk stages (SessionFiles). */
    private static final String XCLIPSE_LAYER_DIR = "/usr/local/lib/droiddeck-xclipse";
    private static final String ADDON_MARKER = ".droiddeck-addon-pack";
    private static final String ADDON_STAGE_PREFIX = ".addon-stage-";
    private static volatile Profile profileOverride;

    private SystemVulkanDriver() {}

    /**
     * The first vendor HAL present, or null. An Exynos with a Mali (Exynos 1280/1380: /dev/mali0)
     * may name its HAL vulkan.samsung.so too; that is a Mali, not an Xclipse, and is not taken.
     */
    public static Profile profile() {
        Profile override = profileOverride;
        return override != null ? override : findProfile(path -> new File(path).isFile(), new File("/dev/mali0").exists());
    }

    @VisibleForTesting
    public static void setProfileForTest(Profile profile) { profileOverride = profile; }

    static Profile findProfile(Predicate<String> present, boolean maliDevice) {
        if (present.test(Profile.POWERVR.halPath)) return Profile.POWERVR;
        if (!maliDevice && present.test(Profile.XCLIPSE.halPath)) return Profile.XCLIPSE;
        return null;
    }

    public static String hal() { Profile selected = profile(); return selected == null ? null : selected.halPath; }

    /** Samsung's Xclipse (Exynos, sgpu) rather than PowerVR. */
    public static boolean isXclipse() { return profile() == Profile.XCLIPSE; }

    /** The system-driver GPU family, for labels. */
    public static String gpuName() { Profile selected = profile(); return selected == null ? "Vulkan" : selected.gpuName; }

    /** Whether a system-driver GPU (PowerVR or Xclipse) is present; the name predates Xclipse. */
    public static boolean isPowerVr() { return profile() == Profile.POWERVR; }
    public static boolean isMali() { return false; }
    public static boolean isSupportedGpu() {
        Profile selected = profile();
        return selected != null && selected.systemSupported;
    }

    /** HAL detection names the GPU; launching also needs the real DRM render node. */
    public static boolean isAvailable() { return isSupportedGpu() && renderNodeAvailable(); }

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
        Profile selected = profile();
        if (selected == null) return false;
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

    static boolean powerVrDriver(String driver) {
        return renderNodeDriver(Profile.POWERVR, driver) || renderNodeDriver(Profile.XCLIPSE, driver);
    }

    static boolean powerVrDevice(File device) {
        return renderNodeDevice(device, Profile.POWERVR) || renderNodeDevice(device, Profile.XCLIPSE);
    }

    static boolean renderNodeAvailable(boolean characterDevice, boolean readable, boolean writable) {
        return characterDevice && readable && writable;
    }

    public static boolean usesDefault(String choice) {
        return usesDefault(isSupportedGpu(), choice);
    }

    static boolean usesDefault(boolean systemGpu, String choice) {
        return systemGpu && (choice == null || choice.isEmpty());
    }

    public static boolean usesSystemVulkan(String choice) {
        return usesSystemVulkan(isSupportedGpu(), choice);
    }

    static boolean usesSystemVulkan(boolean systemGpu, String choice) {
        return systemGpu && (choice == null || choice.isEmpty()
                || SystemVulkanComponentStore.rowDigest(choice) != null);
    }

    public static Provider providerFor(String choice) {
        return providerFor(isSupportedGpu(), choice);
    }

    static Provider providerFor(boolean systemGpu, String choice) {
        if (!usesSystemVulkan(systemGpu, choice)) throw new IllegalArgumentException("Linux driver is not System Vulkan");
        return SystemVulkanComponentStore.rowDigest(choice) == null
                ? Provider.ADDON_PACK : Provider.MANAGED_COMPONENT;
    }

    public static boolean requiresAddonPack(String choice) {
        return requiresAddonPack(isSupportedGpu(), choice);
    }

    static boolean requiresAddonPack(boolean systemGpu, String choice) {
        return usesSystemVulkan(systemGpu, choice) && providerFor(systemGpu, choice) == Provider.ADDON_PACK;
    }

    public static boolean requiresInstalledManagedComponent(String choice) {
        return requiresInstalledManagedComponent(isSupportedGpu(), choice);
    }

    static boolean requiresInstalledManagedComponent(boolean systemGpu, String choice) {
        return usesSystemVulkan(systemGpu, choice) && providerFor(systemGpu, choice) == Provider.MANAGED_COMPONENT;
    }

    public static boolean managesDxvk(String choice) {
        Profile selected = profile();
        return selected != null && selected.dxvkPayload != null && usesSystemVulkan(choice);
    }

    public static synchronized File prepare(Context context) throws IOException {
        return prepareLease(context, "", false).getDirectory();
    }

    public static synchronized RuntimeLease prepareLease(
            Context context, String choice, boolean guestsStopped) throws IOException {
        Profile selected = profile();
        if (selected == null) throw new IOException("System Vulkan driver is unavailable");
        String node = renderNodePath();
        if (node == null) throw new IOException(
                "System " + selected.gpuName + " requires an identifiable, readable and writable DRM render node");
        Provider provider = providerFor(choice);
        if (provider == Provider.MANAGED_COMPONENT) {
            String requested = SystemVulkanComponentStore.rowDigest(choice);
            SystemVulkanComponentStore.Snapshot snapshot =
                    SystemVulkanComponentStore.capture(context, selected, guestsStopped);
            if (!snapshot.getSha256().equals(requested))
                throw new IOException("Selected System Vulkan component is not active yet");
            return new RuntimeLease(provider, selected, snapshot.getVersion(), snapshot.getDirectory(), node, snapshot);
        }
        File directory = prepareAddon(context);
        return new RuntimeLease(provider, selected, directory.getName(), directory, node, null);
    }

    public static synchronized SystemVulkanComponentStore.Snapshot prepareSnapshot(
            Context context, boolean guestsStopped) throws IOException {
        Profile selected = profile();
        if (selected == null) throw new IOException("System Vulkan driver is unavailable");
        return SystemVulkanComponentStore.capture(context, selected, guestsStopped);
    }

    private static File prepareAddon(Context context) throws IOException {
        if (!AddonPack.POWERVR.installed(context))
            throw new IOException("PowerVR graphics add-on is not installed");
        File target = addonRuntimeDirectory(context);
        File base = target.getParentFile();
        if (complete(target)) {
            Files.write(new File(target, ADDON_MARKER).toPath(), target.getName().getBytes(StandardCharsets.US_ASCII));
            return target;
        }
        if (!base.isDirectory() && !base.mkdirs()) throw new IOException("Cannot create system Vulkan directory");
        File staged = new File(base, ADDON_STAGE_PREFIX + UUID.randomUUID());
        try {
            try (FileInputStream archive = new FileInputStream(AddonPack.POWERVR.file(context, "runtime.tzst"))) {
                if (!TarZst.extract(archive, staged)) throw new IOException("Cannot unpack system Vulkan runtime");
            }
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
            Files.write(new File(staged, ADDON_MARKER).toPath(), target.getName().getBytes(StandardCharsets.US_ASCII));
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
        return addonRuntimeDirectory(context);
    }

    private static File addonRuntimeDirectory(Context context) throws IOException {
        String version;
        if (!AddonPack.POWERVR.installed(context)) throw new IOException("PowerVR graphics runtime is not installed");
        try (BufferedReader input = new BufferedReader(new InputStreamReader(
                new FileInputStream(AddonPack.POWERVR.file(context, "version.txt")), StandardCharsets.US_ASCII))) {
            version = input.readLine();
        }
        if (version == null || !version.matches("[0-9a-f]{64}")) throw new IOException("Invalid system Vulkan runtime version");
        return new File(new File(context.getFilesDir(), "system_vulkan"), version);
    }

    /** Collect abandoned stages, and obsolete versions once older guests can no longer load them. */
    public static synchronized void pruneUnused(Context context, boolean guestsStopped) throws IOException {
        Profile selected = profile();
        if (selected != null) SystemVulkanComponentStore.prune(context, selected, guestsStopped);
        if (AddonPack.POWERVR.installed(context)) pruneUnused(addonRuntimeDirectory(context), guestsStopped);
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
            boolean oldAddon = pruneVersions && name.matches("[0-9a-f]{64}")
                    && new File(entry, ADDON_MARKER).isFile();
            boolean abandonedAddonStage = guestsStopped && name.matches(
                    "\\.addon-stage-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
            if ((oldAddon || abandonedAddonStage) && !Files.isSymbolicLink(entry.toPath()))
                FileUtils.delete(entry);
        }
    }

    static boolean complete(File directory) {
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

    public static String icdPath(RuntimeLease lease) {
        return new File(lease.getDirectory(), "icd.json").getPath();
    }

    public static String icdPath(SystemVulkanComponentStore.Snapshot snapshot) {
        return new File(snapshot.getDirectory(), "icd.json").getPath();
    }

    public static void addEnvironment(Context context, List<String> environment) {
        RuntimeLease lease;
        try { lease = prepareLease(context, "", false); }
        catch (IOException error) { throw new IllegalStateException("System Vulkan runtime is unavailable", error); }
        addEnvironment(lease, environment);
    }

    public static void addEnvironment(SystemVulkanComponentStore.Snapshot snapshot, List<String> environment) {
        Profile selected = null;
        for (Profile candidate : Profile.values())
            if (candidate.id.equals(snapshot.getVariant())) selected = candidate;
        if (selected == null || !selected.systemSupported || selected != profile())
            throw new IllegalStateException("System Vulkan component targets an unsupported GPU");
        String node = renderNodePath();
        if (node == null) throw new IllegalStateException("System Vulkan render node is unavailable");
        addEnvironment(new RuntimeLease(Provider.MANAGED_COMPONENT, selected, snapshot.getVersion(),
                snapshot.getDirectory(), node, snapshot), environment);
    }

    public static void addEnvironment(RuntimeLease lease, List<String> environment) {
        Profile selected = lease.getProfile();
        File directory = lease.getDirectory();
        String node = lease.getRenderNode();
        environment.add("HYBRIS_ANDROID_SDK_VERSION=" + Build.VERSION.SDK_INT);
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
            environment.add("HYBRIS_BC_TEXTURES=" + selected.sessionBcTextures);
            environment.add("DROIDDECK_HYBRIS_SESSION_BC_TEXTURES=" + selected.sessionBcTextures);
        }
        if (selected.extraLayerPath != null)
            environment.add("DROIDDECK_HYBRIS_LAYER_PATH=" + selected.extraLayerPath);
        if (selected.dxvkPayload != null)
            environment.add("DROIDDECK_HYBRIS_DXVK=" + selected.dxvkPayload);
        // The Android driver has a render node, but no KMS primary node. SDL presents
        // through the wrapper's Wayland Vulkan swapchain instead of a KMS-style path.
        environment.add("BL_GAMESCOPE_BACKEND=sdl");
        // The Android pvr kernel accepts Vulkan DMA-BUF imports but rejects PRIME GEM handles.
        environment.add("BL_POWERVR_DRM_HANDLES=1");
        environment.add("BL_POWERVR_RENDER_NODE=" + node);
        // Inner Gamescope/Xwayland lacks the bridge's Android-buffer WSI protocols.
        environment.add("BL_STEAM_GL_PRESENT=1");
        environment.add("BL_STEAM_WSI_LAYER_PATH=" + new File(directory, "wsi").getPath());
        // Only actual Proton game launches use the bundled DXVK and X11 WSI layer.
        environment.add("DROIDDECK_POWERVR_RUNTIME=" + directory.getPath());
        environment.add("DROIDDECK_SYSTEM_VULKAN_GPU=" + selected.id);
        environment.add("DROIDDECK_SYSTEM_VULKAN_RUNTIME=" + directory.getPath());
        environment.add("DROIDDECK_SYSTEM_VULKAN_PRESENTATION=" + selected.presentation);
        environment.add("DROIDDECK_SYSTEM_VULKAN_DXVK=" +
                (selected.dxvkPayload == null ? "user" : "managed"));
        environment.add("DROIDDECK_SYSTEM_VULKAN_PROVIDER=" + lease.getProvider().name().toLowerCase(java.util.Locale.ROOT));
        environment.add("DROIDDECK_SYSTEM_VULKAN_VERSION=" + lease.getVersion());
    }

    public static boolean isReady(Context context) {
        return isReady(context, "");
    }

    public static boolean isReady(Context context, String choice) {
        Profile selected = profile();
        if (selected == null || !usesSystemVulkan(choice)) return false;
        if (requiresInstalledManagedComponent(choice))
            return SystemVulkanComponentStore.isReady(context, selected);
        return AddonPack.POWERVR.getAvailable() || AddonPack.POWERVR.installed(context);
    }
}
