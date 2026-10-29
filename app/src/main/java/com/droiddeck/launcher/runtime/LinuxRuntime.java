package com.droiddeck.launcher.runtime;

import com.droiddeck.launcher.core.AddonPack;
import android.content.Context;
import android.os.Process;
import android.system.ErrnoException;
import android.system.Os;
import android.system.StructStat;
import android.system.StructUtsname;
import com.droiddeck.launcher.session.SessionPrefs;


import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

public final class LinuxRuntime {
    public static final String DIR = "linuxfs";
    public static final String SESSION_SCRIPT = "/usr/local/bin/droiddeck-session";
    public static final String MODE_DESKTOP = "desktop";
    public static final String MODE_STEAM = "steam";
    public static final String MODE_RUN = "run";
    public static final String EXTRA_LINUX_MODE = "linux_mode";
    private static final String KGSL_DEVICE = "/dev/kgsl-3d0";
    public static final String GUEST_RUNTIME_DIR = "/run/droiddeck";
    private static final String SYSTEM_FONTS = "/system/fonts";
    private static final String GUEST_SYSTEM_FONTS = "/usr/local/share/fonts/android";
    public static final String DEBUG_LOG_DIR = "DroidDeck";

    public static File logDir(Context context) {
        return new File(context.getFilesDir(), "logs");
    }

    public static File legacyLogDir() {
        return new File(android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_DOWNLOADS), DEBUG_LOG_DIR);
    }

    private LinuxRuntime() {}

    public static File rootDir(Context context) {
        return new File(context.getFilesDir(), DIR);
    }

    public static File sessionRoot(Context context) {
        return new File(context.getFilesDir(), "session");
    }

    public static File liveDir(Context context) {
        // Android may evict cache files even while guest processes are using them.
        File dir = new File(context.getNoBackupFilesDir(), "guest-runtime");
        dir.mkdirs();
        return dir;
    }

    public static File prootTempDir(Context context) {
        File dir = new File(liveDir(context), "proot");
        dir.mkdirs();
        return dir;
    }

    private static final String HOST_DIR = "opt/android-host";
    /** The downloaded add-on pack. The APK does not ship this binary. */
    public static final String PROOT_PACK = "add-on pack";
    /** Termux's unpatched proot inside the rootfs. Developer switch only. */
    public static final String PROOT_ROOTFS = "rootfs fallback";
    public static final String PROOT_MISSING = "missing";

    private static File rootfsProot(Context context) {
        return new File(rootDir(context), HOST_DIR + "/proot");
    }

    private static File rootfsLoader(Context context) {
        return new File(rootDir(context), HOST_DIR + "/loader");
    }

    private static boolean rootfsProotReady(Context context) {
        return rootfsProot(context).isFile() && rootfsLoader(context).isFile();
    }

    /** Patched proot is the add-on pack. The rootfs copy is not that binary. */
    public static boolean patchedProot(Context context) {
        return AddonPack.PROOT.installed(context);
    }

    public static File prootBinary(Context context) {
        if (AddonPack.PROOT.installed(context)) return AddonPack.PROOT.file(context, "libproot.so");
        if (SessionPrefs.allowStockProot(context)) return rootfsProot(context);
        return AddonPack.PROOT.file(context, "libproot.so");
    }

    public static File prootLoader(Context context) {
        if (AddonPack.PROOT.installed(context)) return AddonPack.PROOT.file(context, "libproot-loader.so");
        if (SessionPrefs.allowStockProot(context)) return rootfsLoader(context);
        return AddonPack.PROOT.file(context, "libproot-loader.so");
    }

    /** Where {@link #prootBinary} came from: {@link #PROOT_PACK}, {@link #PROOT_ROOTFS} or {@link #PROOT_MISSING}. */
    public static String prootSource(Context context) {
        File bin = prootBinary(context);
        if (!bin.isFile()) return PROOT_MISSING;
        if (AddonPack.PROOT.installed(context)
                && bin.getPath().equals(AddonPack.PROOT.file(context, "libproot.so").getPath())) return PROOT_PACK;
        return PROOT_ROOTFS;
    }

    public static String prootLibraryPath(Context context) {
        File dir = new File(rootDir(context), HOST_DIR);
        return dir.equals(prootBinary(context).getParentFile()) ? dir.getPath() : "";
    }

    public static boolean isInstalled(Context context) {
        if (LinuxRuntimeInstaller.isRemoving() || LinuxRuntimeInstaller.hasRemovalPending(context)) return false;
        File root = rootDir(context);
        return (new File(root, "usr/bin/gamescope").isFile() || new File(root, "usr/games/gamescope").isFile())
                && (new File(root, SESSION_SCRIPT.substring(1)).isFile()
                        || new File(root, LegacyNames.SESSION_SCRIPT.substring(1)).isFile())
                && (patchedProot(context) || rootfsProotReady(context));
    }

    public static File vulkanIcd(Context context) {
        File icdDir = new File(rootDir(context), "usr/share/vulkan/icd.d");
        File[] manifests = icdDir.listFiles((dir, name) -> name.endsWith(".json"));
        if (manifests == null) return null;
        for (File manifest : manifests) {
            if (manifest.getName().contains("freedreno")) return manifest;
        }
        return manifests.length > 0 ? manifests[0] : null;
    }

    public static List<String> command(Context context, File sessionRoot, File runtimeDir,
                                       File externalStorage, List<String> guestCommand) {
        return command(context, sessionRoot, runtimeDir, externalStorage, null, guestCommand);
    }

    public static List<String> command(Context context, File sessionRoot, File runtimeDir,
                                       File externalStorage, List<String> extraBinds,
                                       List<String> guestCommand) {
        File root = rootDir(context);
        List<String> binds = binds(context, sessionRoot, runtimeDir, externalStorage, extraBinds);
        if (sessionRoot != null) lastBinds = binds;
        return commandWithBinds(context, root, binds, guestCommand);
    }

    public static List<String> commandWithBinds(Context context, File root, List<String> binds,
                                                List<String> guestCommand) {
        List<String> cmd = prootPrefix(context, root, "/root");
        for (String spec : binds) bind(cmd, spec);
        cmd.addAll(guestCommand);
        return cmd;
    }

    private static volatile List<String> lastBinds;

    public static List<String> lastBinds(Context context) {
        List<String> binds = lastBinds;
        if (binds != null) return binds;
        return binds(context, null, null, android.os.Environment.getExternalStorageDirectory(), null);
    }

    public static List<String> prootPrefix(Context context, File root, String cwd) {
        return prootPrefix(context, root, cwd, false);
    }

    public static List<String> prootPrefix(Context context, File root, String cwd, boolean fakeRoot) {
        List<String> cmd = new ArrayList<>();
        cmd.add(prootBinary(context).getPath());
        cmd.add("--kill-on-exit");
        cmd.add("--kernel-release=" + guestUtsname(SessionPrefs.guestHostname(context)));
        int uid = Process.myUid();
        if (fakeRoot) {
            cmd.add("-0");
        } else {
            cmd.add("-i");
            cmd.add(uid + ":" + uid);
        }
        cmd.add("-r");
        cmd.add(root.getPath());
        cmd.add("-w");
        cmd.add(cwd);
        return cmd;
    }

    public static List<String> binds(Context context, File sessionRoot, File runtimeDir,
                                     File externalStorage, List<String> extraBinds) {
        File root = rootDir(context);
        LegacyNames.migrateEtc(root);
        List<String> cmd = new ArrayList<>();
        bind(cmd, "/dev");
        bind(cmd, "/proc");
        bind(cmd, "/sys");
        bind(cmd, DeviceControlsComponent.prepareBacklight(context, root).getPath() + ":/sys/class/backlight");
        bind(cmd, "/dev/urandom:/dev/random");
        bind(cmd, "/proc/self/fd:/dev/fd");
        bind(cmd, "/proc/self/fd/0:/dev/stdin");
        bind(cmd, "/proc/self/fd/1:/dev/stdout");
        bind(cmd, "/proc/self/fd/2:/dev/stderr");
        bind(cmd, new File(root, "etc/droiddeck/empty").getPath() + ":/sys/fs/selinux");
        bind(cmd, context.getFilesDir().getPath());
        bind(cmd, context.getCacheDir().getPath());
        bind(cmd, liveDir(context).getPath());
        if (runtimeDir != null) {
            bind(cmd, runtimeDir.getPath());
            bind(cmd, runtimeDir.getPath() + ":" + GUEST_RUNTIME_DIR);
        }
        if (sessionRoot != null) bind(cmd, sessionRoot.getPath());
        if (externalStorage != null && externalStorage.isDirectory()) {
            bind(cmd, externalStorage.getPath());
        }
        File shm = new File(liveDir(context), "shm");
        shm.mkdirs();
        bind(cmd, shm.getPath() + ":/dev/shm");
        bindSystemFonts(cmd, root);

        File fakeProc = new File(root, "etc/droiddeck/proc");
        File pciDevices = new File(fakeProc, "pci_devices");
        if (!pciDevices.isFile()) {
            try {
                pciDevices.getParentFile().mkdirs();
                pciDevices.createNewFile();
            } catch (java.io.IOException e) {
            }
        }
        String[][] seeded = {
                {"file-max", "9223372036854775807\n"},
                {"overcommit_memory", "0\n"},
        };
        for (String[] entry : seeded) {
            File fake = new File(fakeProc, entry[0]);
            if (fake.isFile()) continue;
            try {
                Files.write(fake.toPath(), entry[1].getBytes(StandardCharsets.US_ASCII));
            } catch (IOException e) {
            }
        }
        String[][] procFiles = {
                {"pci_devices", "/proc/bus/pci/devices"},
                {"stat", "/proc/stat"},
                {"version", "/proc/version"},
                {"loadavg", "/proc/loadavg"},
                {"uptime", "/proc/uptime"},
                {"vmstat", "/proc/vmstat"},
                {"cap_last_cap", "/proc/sys/kernel/cap_last_cap"},
                {"overflowuid", "/proc/sys/kernel/overflowuid"},
                {"overflowgid", "/proc/sys/kernel/overflowgid"},
                {"file-max", "/proc/sys/fs/file-max"},
                {"overcommit_memory", "/proc/sys/vm/overcommit_memory"},
        };
        for (String[] entry : procFiles) {
            File fake = new File(fakeProc, entry[0]);
            boolean live = false;
            if (extraBinds != null) for (String spec : extraBinds) live |= spec.endsWith(":" + entry[1]);
            if (!live && fake.isFile() && !new File(entry[1]).canRead()) {
                bind(cmd, fake.getPath() + ":" + entry[1]);
            }
        }
        if (unreadablePciDevice()) {
            bind(cmd, new File(root, "etc/droiddeck/empty").getPath() + ":/sys/bus/pci/devices");
        }
        bindGpuNode(context, cmd);
        bindAdrenoStats(cmd, extraBinds);
        bindCpuTemps(cmd, root);
        if (extraBinds != null) {
            for (String spec : extraBinds) bind(cmd, spec);
        }
        List<String> specs = new ArrayList<>();
        for (int i = 0; i + 1 < cmd.size(); i += 2) specs.add(cmd.get(i + 1));
        return specs;
    }

    private static boolean unreadablePciDevice() {
        File[] devices = new File("/sys/bus/pci/devices").listFiles();
        if (devices == null) return false;
        for (File device : devices) {
            if (!new File(device, "vendor").canRead()) return true;
        }
        return false;
    }

    private static void bindGpuNode(Context context, List<String> cmd) {
        StructStat st;
        try {
            st = Os.stat(KGSL_DEVICE);
        } catch (ErrnoException e) {
            return;
        }
        long dev = st.st_rdev;
        long major = ((dev >> 8) & 0xfff) | ((dev >> 32) & ~0xfffL);
        long minor = (dev & 0xff) | ((dev >> 12) & ~0xffL);
        String node = "renderD" + minor;
        File base = new File(liveDir(context), "drm");
        File dri = new File(base, "dri");
        File device = new File(base, "sys/" + major + ":" + minor + "/device");
        File drm = new File(device, "drm/" + node);
        try {
            if ((!dri.isDirectory() && !dri.mkdirs()) || (!drm.isDirectory() && !drm.mkdirs())) {
                return;
            }
            new File(dri, node).createNewFile();
            Files.write(new File(drm, "dev").toPath(),
                    (major + ":" + minor + "\n").getBytes(StandardCharsets.UTF_8));
            Files.write(new File(device, "uevent").toPath(),
                    "DRIVER=kgsl-3d0\nMODALIAS=platform:kgsl-3d0\n".getBytes(StandardCharsets.UTF_8));
            File subsystem = new File(device, "subsystem");
            if (!Files.isSymbolicLink(subsystem.toPath())) {
                Os.symlink("/sys/bus/platform", subsystem.getPath());
            }
            File driver = new File(device, "driver");
            if (!Files.isSymbolicLink(driver.toPath())) {
                Os.symlink("/sys/bus/platform/drivers/msm_drm", driver.getPath());
            }
        } catch (IOException | ErrnoException e) {
            return;
        }
        bind(cmd, new File(base, "sys").getPath() + ":/sys/dev/char");
        bind(cmd, dri.getPath() + ":/dev/dri");
        bind(cmd, KGSL_DEVICE + ":/dev/dri/" + node);
        bindDrmClass(base, cmd, node, major + ":" + minor);
    }

    private static void bindDrmClass(File base, List<String> cmd, String node, String devNumbers) {
        if (new File("/sys/class/drm").list() != null) return;
        File drmClass = new File(base, "class");
        try {
            if (!drmClass.isDirectory() && !drmClass.mkdirs()) return;
            File[] old = drmClass.listFiles();
            if (old != null) for (File f : old) Files.deleteIfExists(f.toPath());
            Os.symlink("/sys/dev/char/" + devNumbers, new File(drmClass, node).getPath());
        } catch (IOException | ErrnoException e) {
            return;
        }
        bind(cmd, drmClass.getPath() + ":/sys/class/drm");
        android.util.Log.i("LinuxRuntime", "hud: /sys/class/drm is not listable here; the session's lists " + node);
        if (new File("/sys/class/powercap").list() == null) {
            File empty = new File(base, "empty-powercap");
            if (empty.isDirectory() || empty.mkdirs()) bind(cmd, empty.getPath() + ":/sys/class/powercap");
        }
    }

    public static String cpuTempSource() {
        for (File zone : cpuTempZones()) {
            File temp = new File(zone, "temp");
            if (temp.canRead()) return temp.getPath();
        }
        return null;
    }

    public static String gpuTempSource() {
        String kgsl = "/sys/class/kgsl/kgsl-3d0/";
        return firstReadable(kgsl + "temp", kgsl + "devfreq/temp", thermalZone("gpu"));
    }

    public static String gpuLoadSource() {
        String kgsl = "/sys/class/kgsl/kgsl-3d0/";
        return firstReadable(kgsl + "gpu_busy_percentage", kgsl + "devfreq/gpu_load");
    }

    private static void bindAdrenoStats(List<String> cmd, List<String> extraBinds) {
        String kgsl = "/sys/class/kgsl/kgsl-3d0/";
        String gpuTemp = gpuTempSource();
        String[][] stats = {
                {gpuLoadSource(), "/sys/kernel/debug/dri/0/perf_now"},
                {firstReadable(kgsl + "devfreq/cur_freq", kgsl + "gpuclk"),
                        "/sys/devices/platform/soc@0/3d00000.gpu/devfreq/3d00000.gpu/cur_freq"},
                {gpuTemp, "/sys/class/thermal/thermal_zone28/temp"},
                {gpuTemp, "/sys/class/thermal/thermal_zone26/temp"},
                {ddrTempSource(), "/sys/class/thermal/thermal_zone22/temp"},
        };
        for (String[] stat : stats) {
            boolean fed = false;
            if (extraBinds != null) for (String spec : extraBinds) fed |= spec.endsWith(":" + stat[1]);
            if (stat[0] != null && !fed) bind(cmd, stat[0] + ":" + stat[1]);
        }
        android.util.Log.i("LinuxRuntime", "hud sources: gpu load " + orNone(stats[0][0]) + ", gpu clock "
                + orNone(stats[1][0]) + ", gpu temp " + orNone(gpuTemp) + ", ddr temp " + orNone(stats[4][0])
                + ", thermal zones " + thermalZones().size()
                + ", kgsl gpubusy " + (new File(kgsl + "gpubusy").canRead() ? "readable" : "refused"));
    }

    private static String orNone(String path) {
        return path != null ? path : "none";
    }

    private static String ddrTempSource() {
        return thermalZone("ddr");
    }

    // MangoHud reads the GPU and DDR temperatures from these zone numbers (bindAdrenoStats). On phones
    // where they are CPU zones, their type follows the temperature put there, so they stay out of the
    // CPU average.
    private static java.util.Map<String, String> replacedZoneTypes() {
        java.util.Map<String, String> replaced = new java.util.HashMap<>();
        if (gpuTempSource() != null) {
            replaced.put("thermal_zone28", "gpu-thermal");
            replaced.put("thermal_zone26", "gpu-thermal");
        }
        if (ddrTempSource() != null) replaced.put("thermal_zone22", "ddr-thermal");
        return replaced;
    }

    /** The zones the session's MangoHud averages for the CPU temperature (all typed cpuN-thermal there). */
    public static List<File> cpuTempZones() {
        java.util.Map<String, String> replaced = replacedZoneTypes();
        List<File> zones = new ArrayList<>();
        for (java.util.Map.Entry<String, File> zone : new java.util.TreeMap<>(thermalZones()).entrySet()) {
            String type = zone.getKey();
            if (type.contains("cpu") && !type.contains("gpu") && !replaced.containsKey(zone.getValue().getName())) {
                zones.add(zone.getValue());
            }
        }
        return zones;
    }

    private static void bindCpuTemps(List<String> cmd, File root) {
        File name = new File(root, "etc/droiddeck/cpu-thermal-type");
        try {
            if (!name.isFile()) Files.write(name.toPath(), "cpu0-thermal\n".getBytes(StandardCharsets.US_ASCII));
        } catch (IOException e) {
            return;
        }
        java.util.Map<String, String> replaced = replacedZoneTypes();
        for (java.util.Map.Entry<String, File> zone : thermalZones().entrySet()) {
            String type = zone.getKey();
            String other = replaced.get(zone.getValue().getName());
            if (other != null) {
                File typeFile = new File(root, "etc/droiddeck/" + other + "-type");
                try {
                    if (!typeFile.isFile()) Files.write(typeFile.toPath(), (other + "\n").getBytes(StandardCharsets.US_ASCII));
                    bind(cmd, typeFile.getPath() + ":" + new File(zone.getValue(), "type").getPath());
                } catch (IOException ignored) {
                }
            } else if (type.contains("cpu") && !type.contains("gpu") && !type.matches("cpu\\d-(top-)?thermal")) {
                bind(cmd, name.getPath() + ":" + new File(zone.getValue(), "type").getPath());
            }
        }
    }

    private static String firstReadable(String... paths) {
        for (String path : paths) {
            if (path != null && new File(path).canRead()) return path;
        }
        return null;
    }

    private static String thermalZone(String sensor) {
        java.util.Map<String, File> zones = thermalZones();
        File zone = zones.get(sensor);
        for (java.util.Map.Entry<String, File> e : new java.util.TreeMap<>(zones).entrySet()) {
            if (zone == null && e.getKey().contains(sensor)) zone = e.getValue();
        }
        return zone != null ? new File(zone, "temp").getPath() : null;
    }

    private static java.util.Map<String, File> thermalZones() {
        java.util.Map<String, File> byType = new java.util.HashMap<>();
        File[] zones = new File("/sys/class/thermal").listFiles((dir, n) -> n.startsWith("thermal_zone"));
        if (zones == null) return byType;
        for (File zone : zones) {
            try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(new File(zone, "type")))) {
                byType.putIfAbsent(String.valueOf(r.readLine()).trim().toLowerCase(java.util.Locale.ROOT), zone);
            } catch (IOException ignored) {
            }
        }
        return byType;
    }

    private static void bindSystemFonts(List<String> cmd, File root) {
        File fonts = new File(SYSTEM_FONTS);
        File target = new File(root, GUEST_SYSTEM_FONTS.substring(1));
        if (!fonts.isDirectory() || !fonts.canRead() || !new File(root, "usr/local").isDirectory()) return;
        if (target.isDirectory() || target.mkdirs()) {
            bind(cmd, SYSTEM_FONTS + ":" + GUEST_SYSTEM_FONTS);
        }
    }

    private static void bind(List<String> cmd, String spec) {
        cmd.add("-b");
        cmd.add(spec);
    }

    static String guestUtsname(String hostname) {
        StructUtsname host = Os.uname();
        return "\\" + host.sysname + "\\" + hostname + "\\" + host.release
                + "\\" + host.version + "\\" + host.machine + "\\localdomain\\-1\\";
    }

    public static synchronized void writeAccounts(Context context) throws IOException {
        File root = rootDir(context);
        int uid = Process.myUid();
        mergeAccounts(new File(root, "etc/passwd"), 2,
                "root:x:" + uid + ":" + uid + ":root:/root:/bin/bash", "root:x:0:0:root:/root:/bin/bash", uid);
        mergeAccounts(new File(root, "etc/group"), 2,
                "root:x:" + uid + ":", "root:x:0:", uid);
        File group = new File(root, "etc/group");
        String groups = new String(Files.readAllBytes(group.toPath()), StandardCharsets.UTF_8);
        writeIfChanged(group, groups, withStorageGroups(groups));
    }

    private static final String[][] STORAGE_GROUPS = {
            {"sdcard_rw", "1015"}, {"media_rw", "1023"}, {"everybody", "9997"}};

    static String withStorageGroups(String groups) {
        StringBuilder out = new StringBuilder();
        List<String> present = new ArrayList<>();
        for (String line : groups.split("\n")) {
            if (line.trim().isEmpty()) continue;
            String[] fields = line.split(":", -1);
            if (fields.length >= 4) {
                for (String[] storage : STORAGE_GROUPS) {
                    if (fields[0].equals(storage[0])) present.add(storage[0]);
                    if (!fields[2].equals(storage[1])) continue;
                    present.add(storage[0]);
                    if (!java.util.Arrays.asList(fields[3].split(",")).contains("root")) {
                        fields[3] = fields[3].isEmpty() ? "root" : fields[3] + ",root";
                        line = String.join(":", fields);
                    }
                }
            }
            out.append(line).append('\n');
        }
        for (String[] storage : STORAGE_GROUPS) {
            if (!present.contains(storage[0])) {
                out.append(storage[0]).append(":x:").append(storage[1]).append(":root\n");
            }
        }
        return out.toString();
    }

    static String mergedAccounts(String existing, int idField, String own, String zero, int uid) {
        StringBuilder out = new StringBuilder(own).append('\n').append(zero).append('\n');
        if (existing == null) return out.toString();
        String ownId = Integer.toString(uid);
        for (String line : existing.split("\n")) {
            if (line.trim().isEmpty()) continue;
            String[] fields = line.split(":", -1);
            if (fields.length <= idField) continue;
            if (fields[0].equals("root") || fields[idField].equals("0") || fields[idField].equals(ownId)) continue;
            out.append(line).append('\n');
        }
        return out.toString();
    }

    private static void mergeAccounts(File file, int idField, String own, String zero, int uid) throws IOException {
        String existing = file.isFile() ? new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8) : null;
        writeIfChanged(file, existing, mergedAccounts(existing, idField, own, zero, uid));
    }

    private static void writeIfChanged(File file, String existing, String merged) throws IOException {
        if (merged.equals(existing)) return;
        File staged = File.createTempFile(file.getName(), ".staged", file.getParentFile());
        Files.write(staged.toPath(), merged.getBytes(StandardCharsets.UTF_8));
        Files.move(staged.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE);
    }
}
