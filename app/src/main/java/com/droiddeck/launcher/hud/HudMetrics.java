/*
 * FusionHUD — a lightweight in-game performance HUD for Android / Winlator overlays.
 * Copyright (C) The412Banner  <https://github.com/The412Banner>
 *
 * Licensed under the GNU General Public License v3.0 (see LICENSE), with an
 * ADDITIONAL attribution requirement under GPL-3.0 §7(b) (see ATTRIBUTION.md):
 * any use, fork, or distribution must preserve credit to "The412Banner" and a
 * link to https://github.com/The412Banner/FusionHUD in the project documentation
 * AND in the in-app credits/about screen.
 *
 * Ported to DroidDeck from FusionHUD (Bannerlator's HUD) and modified for DroidDeck.
 * Metric readers from @xXJSONDeruloXx's work.
 */

package com.droiddeck.launcher.hud;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.Build;
import android.os.SystemClock;


import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

public class HudMetrics {
    private final Context context;

    public HudMetrics(Context context) { this.context = context; }

    private MangoReadings mango;
    public void setMangoReadings(MangoReadings readings) { mango = readings; }

    private Long lastCpuTotal = null;
    private Long lastCpuIdle = null;

    public Integer getCpuUsagePercent() {
        String line = readFirstLine("/proc/stat");
        if (line != null) {
            String[] parts = line.trim().split("\\s+");
            if (parts.length >= 5 && "cpu".equals(parts[0])) {
                long total = 0, idle = 0, iowait = 0;
                boolean ok = true;
                for (int i = 1; i < parts.length; i++) {
                    try {
                        long v = Long.parseLong(parts[i]);
                        total += v;
                        if (i == 4) idle = v;
                        else if (i == 5) iowait = v;
                    } catch (NumberFormatException e) { ok = false; break; }
                }
                if (ok) {
                    long idleTotal = idle + iowait;
                    Long prevTotal = lastCpuTotal, prevIdle = lastCpuIdle;
                    lastCpuTotal = total;
                    lastCpuIdle = idleTotal;
                    if (prevTotal != null && prevIdle != null) {
                        long dTotal = total - prevTotal;
                        long dIdle = idleTotal - prevIdle;
                        if (dTotal > 0) {
                            long usage = (Math.max(0, dTotal - dIdle) * 100L) / dTotal;
                            return clampPercent((int) usage);
                        }
                    }
                }
            }
        }
        return readCpuUsagePercentFromFrequency();
    }

    private Integer readCpuUsagePercentFromFrequency() {
        long currentTotal = 0, maxTotal = 0;
        int cores = Runtime.getRuntime().availableProcessors();
        for (int i = 0; i < cores; i++) {
            Long cur = readLongFromLine("/sys/devices/system/cpu/cpu" + i + "/cpufreq/scaling_cur_freq");
            Long max = readLongFromLine("/sys/devices/system/cpu/cpu" + i + "/cpufreq/cpuinfo_max_freq");
            if (cur != null && max != null && max > 0L) {
                currentTotal += Math.max(0, Math.min(cur, max));
                maxTotal += max;
            }
        }
        if (maxTotal <= 0L) return null;
        return clampPercent((int) ((currentTotal * 100L) / maxTotal));
    }

    private List<String> gpuUsagePathsCache = null;
    private Long lastMaliGpuInfoMs = null;
    private long lastMaliGpuInfoWallMs = 0L;

    public Integer getGpuUsagePercent() {
        for (String path : discoverGpuUsagePaths()) {
            Integer p = readGpuUsageSample(path);
            if (p != null) return p;
        }
        return null;
    }

    private List<String> discoverGpuUsagePaths() {
        if (gpuUsagePathsCache != null) return gpuUsagePathsCache;
        LinkedHashSet<String> candidates = new LinkedHashSet<>();

        for (String p : GPU_USAGE_STATIC_PATHS) {
            if (new File(p).canRead()) candidates.add(p);
        }

        File platformDir = new File("/sys/devices/platform");
        File[] platformNodes = platformDir.listFiles(File::isDirectory);
        if (platformNodes != null) {
            for (File node : platformNodes) {
                String name = node.getName().toLowerCase(Locale.US);
                boolean looksLikeGpu = false;
                for (String t : GPU_NODE_TOKENS) {
                    if (name.contains(t)) { looksLikeGpu = true; break; }
                }
                if (!looksLikeGpu) continue;
                for (String fileName : GPU_USAGE_FILES) {
                    File f = new File(node, fileName);
                    if (f.canRead()) candidates.add(f.getPath());
                }
            }
        }

        File[] devfreqRoots = {
            new File("/sys/class/devfreq"),
            new File("/sys/devices/virtual/devfreq"),
        };
        for (File root : devfreqRoots) {
            if (!root.isDirectory()) continue;
            File[] nodeDirs = root.listFiles(File::isDirectory);
            if (nodeDirs == null) continue;
            for (File node : nodeDirs) {
                String nodePath = node.getPath().toLowerCase(Locale.US);
                boolean looksLikeGpu = false;
                for (String t : GPU_NODE_TOKENS) {
                    if (nodePath.contains(t)) { looksLikeGpu = true; break; }
                }
                for (String fileName : GPU_USAGE_FILES) {
                    File f = new File(node, fileName);
                    if (!f.canRead()) continue;
                    if (looksLikeGpu || fileName.equals("gpu_busy_percentage")
                            || fileName.equals("gpu_busy_percent") || fileName.equals("gpuinfo")) {
                        candidates.add(f.getPath());
                    }
                }
            }
        }

        gpuUsagePathsCache = new ArrayList<>(candidates);
        return gpuUsagePathsCache;
    }

    private static final String[] GPU_USAGE_STATIC_PATHS = {
        "/sys/class/kgsl/kgsl-3d0/gpubusy",
        "/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage",
        "/sys/class/kgsl/kgsl-3d0/devfreq/gpu_load",
        "/sys/class/misc/mali0/device/utilisation",
        "/sys/class/misc/mali0/device/utilization",
        "/sys/class/misc/mali0/device/gpuinfo",
        "/sys/devices/platform/mali/utilization",
        "/sys/kernel/gpu/gpu_busy",
        "/sys/devices/platform/gpusysfs/gpu_busy",
        "/sys/class/misc/pvrsrvkm/device/utilisation",
        "/sys/class/pvr/utilisation",
        "/sys/class/pvr/gpu_utilisation",
        "/sys/class/drm/card0/device/gpu_busy_percent",
        "/sys/class/devfreq/gpu/load",
        "/sys/kernel/ged/hal/gpu_utilization",
        "/sys/module/ged/parameters/gpu_loading",
    };
    private static final String[] GPU_USAGE_FILES = {
        "gpu_busy_percentage", "gpu_busy_percent", "gpu_load", "utilisation", "utilization",
        "load", "gpu_busy", "gpuinfo",
    };
    private static final String[] GPU_NODE_TOKENS = {
        "gpu", "mali", "g3d", "kgsl", "panfrost", "pvr", "powervr", "xclipse", "sgpu",
    };

    private Integer readGpuUsageSample(String path) {
        String fileName = path.substring(path.lastIndexOf('/') + 1);
        switch (fileName) {
            case "gpubusy": {
                String raw = readFirstLine(path);
                if (raw == null) return null;
                String[] parts = raw.trim().split("\\s+");
                if (parts.length < 2) return null;
                Long busy = parseLong(parts[0]);
                Long total = parseLong(parts[1]);
                if (busy == null || total == null || total <= 0L) return null;
                return clampPercent((int) ((busy * 100L) / total));
            }
            case "gpuinfo": {
                String line = readNthLine(path, 1);
                if (line == null) return null;
                String[] toks = line.trim().split("\\s+");
                Long gpuMs = parseLong(toks[toks.length - 1]);
                if (gpuMs == null) return null;
                long now = SystemClock.elapsedRealtime();
                Long prevMs = lastMaliGpuInfoMs;
                long prevWall = lastMaliGpuInfoWallMs;
                lastMaliGpuInfoMs = gpuMs;
                lastMaliGpuInfoWallMs = now;
                if (prevMs == null || prevWall <= 0L) return null;
                long wallDelta = now - prevWall;
                if (wallDelta <= 0L) return null;
                long gpuDelta = Math.max(0L, gpuMs - prevMs);
                return clampPercent((int) ((gpuDelta * 100L) / wallDelta));
            }
            default:
                return readPercentFromLine(path);
        }
    }

    private Integer readPercentFromLine(String path) {
        String raw = readFirstLine(path);
        if (raw == null) return null;
        for (String tok : raw.trim().split("\\s+")) {
            String digits = tok.replaceAll("[^0-9]", "");
            if (!digits.isEmpty()) {
                Integer v = parseInt(digits);
                return v == null ? null : clampPercent(v);
            }
        }
        return null;
    }

    private List<String[]> thermalZonesCache = null;

    public Integer getCpuTempC() {
        return readTemperatureC(discoverPrioritizedCpuTempPaths());
    }

    public Integer getGpuTempC() {
        ArrayList<String> paths = new ArrayList<>();
        paths.add("/sys/class/kgsl/kgsl-3d0/temp");
        paths.add("/sys/class/kgsl/kgsl-3d0/devfreq/temp");
        paths.add("/sys/class/misc/mali0/device/temp");
        paths.add("/sys/kernel/gpu/temp");
        paths.addAll(discoverPrioritizedGpuTempPaths());
        return readTemperatureC(paths);
    }

    private List<String> discoverPrioritizedCpuTempPaths() {
        return prioritizePaths(type -> {
            if (type.contains("gpu")) return null;
            if (type.contains("cpu-silicon")) return 0;
            if (type.contains("cpu-0")) return 1;
            if (type.contains("cpuss")) return 2;
            if (type.contains("mtktscpu")) return 2;
            if (type.contains("cpu")) return 2;
            if (type.contains("s5p-tmu")) return 3;
            if (type.contains("soc")) return 4;
            if (type.contains("cputop")) return 5;
            if (type.contains("tsens")) return 6;
            if (type.contains("cluster")) return 7;
            if (type.contains("big") || type.contains("little")) return 8;
            return null;
        });
    }

    private List<String> discoverPrioritizedGpuTempPaths() {
        return prioritizePaths(type -> {
            if (type.contains("gpu-silicon")) return 0;
            if (type.contains("gpuss")) return 1;
            if (type.contains("mtktsgpu")) return 1;
            if (type.contains("gpu-virt")) return 2;
            if (type.contains("gpu")) return 2;
            if (type.contains("g3d")) return 3;
            if (type.contains("kgsl")) return 4;
            if (type.contains("mali")) return 5;
            if (type.contains("xclipse") || type.contains("sgpu")) return 6;
            return null;
        });
    }

    private interface Ranker { Integer rank(String type); }

    private List<String> prioritizePaths(Ranker ranker) {
        List<String[]> zones = discoverAllThermalZones();
        ArrayList<int[]> order = new ArrayList<>();
        for (int i = 0; i < zones.size(); i++) {
            Integer r = ranker.rank(zones.get(i)[0]);
            if (r != null) order.add(new int[]{i, r});
        }
        order.sort((a, b) -> {
            if (a[1] != b[1]) return Integer.compare(a[1], b[1]);
            return zones.get(a[0])[1].compareTo(zones.get(b[0])[1]);
        });
        ArrayList<String> result = new ArrayList<>();
        for (int[] e : order) result.add(zones.get(e[0])[1]);
        return result;
    }

    private List<String[]> discoverAllThermalZones() {
        if (thermalZonesCache != null) return thermalZonesCache;
        ArrayList<String[]> zones = new ArrayList<>();
        LinkedHashSet<String> seenPaths = new LinkedHashSet<>();
        File[] thermalDirs = {
            new File("/sys/class/thermal"),
            new File("/sys/devices/virtual/thermal"),
        };
        for (File dir : thermalDirs) {
            File[] zoneDirs = dir.listFiles((d, name) -> name.startsWith("thermal_zone"));
            if (zoneDirs == null) continue;
            for (File zone : zoneDirs) {
                if (!zone.isDirectory()) continue;
                String type = readFirstLine(new File(zone, "type").getPath());
                if (type == null) continue;
                type = type.trim().toLowerCase(Locale.US);
                String tempPath = new File(zone, "temp").getPath();
                if (seenPaths.add(tempPath)) zones.add(new String[]{type, tempPath});
            }
        }
        thermalZonesCache = zones;
        return zones;
    }

    private Integer readTemperatureC(List<String> paths) {
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (String path : paths) {
            if (!seen.add(path)) continue;
            Integer raw = readIntFromLine(path);
            if (raw == null) continue;
            int celsius = raw > 1000 ? (raw + 500) / 1000 : raw;
            if (celsius >= 1 && celsius <= 150) return celsius;
        }
        return null;
    }

    private String resolveTempPath(List<String> paths) {
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (String path : paths) {
            if (!seen.add(path)) continue;
            Integer raw = readIntFromLine(path);
            if (raw == null) continue;
            int celsius = raw > 1000 ? (raw + 500) / 1000 : raw;
            if (celsius >= 1 && celsius <= 150) return path;
        }
        return null;
    }

    public enum TempSensor { CPU, GPU, BATTERY }

    public static final class Thresholds {
        public final int amberC;
        public final int redC;
        Thresholds(int amberC, int redC) { this.amberC = amberC; this.redC = redC; }
    }

    public static final int TEMP_COLOR_OK    = 0xFF66BB6A;
    public static final int TEMP_COLOR_AMBER = 0xFFFFB74D;
    public static final int TEMP_COLOR_RED   = 0xFFEF5350;

    private static final int DEFAULT_RED_CPU = 90;
    private static final int DEFAULT_RED_GPU = 90;
    private static final int DEFAULT_RED_BAT = 48;

    public static int amberForRed(int redC) {
        return Math.round(redC * 0.88f);
    }

    public static final class TempDisplay {
        public final boolean fahrenheit;
        public final boolean colorBands;
        public final boolean auto;
        public final int redCpu, redGpu, redBat;

        public TempDisplay(boolean fahrenheit, boolean colorBands, boolean auto,
                           int redCpu, int redGpu, int redBat) {
            this.fahrenheit = fahrenheit;
            this.colorBands = colorBands;
            this.auto = auto;
            this.redCpu = redCpu;
            this.redGpu = redGpu;
            this.redBat = redBat;
        }

        public static TempDisplay from(KeyValueSet cfg) {
            if (cfg == null) return new TempDisplay(false, true, true,
                DEFAULT_RED_CPU, DEFAULT_RED_GPU, DEFAULT_RED_BAT);
            return new TempDisplay(
                cfg.get("tempUnit", "c").equalsIgnoreCase("f"),
                !cfg.get("tempBands", "1").equals("0"),
                !cfg.get("tempAuto", "1").equals("0"),
                parseInt(cfg.get("tempRedCpu", ""), DEFAULT_RED_CPU),
                parseInt(cfg.get("tempRedGpu", ""), DEFAULT_RED_GPU),
                parseInt(cfg.get("tempRedBat", ""), DEFAULT_RED_BAT));
        }

        int manualRedFor(TempSensor s) {
            switch (s) {
                case GPU:     return redGpu;
                case BATTERY: return redBat;
                default:      return redCpu;
            }
        }

        private static int parseInt(String s, int fallback) {
            try { return s == null || s.isEmpty() ? fallback : Integer.parseInt(s.trim()); }
            catch (NumberFormatException e) { return fallback; }
        }
    }

    private final java.util.EnumMap<TempSensor, Thresholds> autoThresholdCache =
        new java.util.EnumMap<>(TempSensor.class);
    private final java.util.EnumSet<TempSensor> autoThresholdResolved =
        java.util.EnumSet.noneOf(TempSensor.class);

    public Thresholds resolveThresholds(TempSensor sensor, TempDisplay display) {
        if (display != null && !display.auto) {
            int red = display.manualRedFor(sensor);
            return new Thresholds(amberForRed(red), red);
        }
        if (!autoThresholdResolved.contains(sensor)) {
            autoThresholdResolved.add(sensor);
            Thresholds fromDevice = readTripPointThresholds(sensor);
            if (fromDevice != null) autoThresholdCache.put(sensor, fromDevice);
        }
        Thresholds cached = autoThresholdCache.get(sensor);
        if (cached != null) return cached;
        int red = sensor == TempSensor.GPU ? DEFAULT_RED_GPU
                : sensor == TempSensor.BATTERY ? DEFAULT_RED_BAT
                : DEFAULT_RED_CPU;
        return new Thresholds(amberForRed(red), red);
    }

    private Thresholds readTripPointThresholds(TempSensor sensor) {
        String tempPath;
        switch (sensor) {
            case GPU:
                tempPath = resolveTempPath(discoverPrioritizedGpuTempPaths());
                break;
            case BATTERY:
                return null;
            default:
                tempPath = resolveTempPath(discoverPrioritizedCpuTempPaths());
        }
        if (tempPath == null) return null;
        File zoneDir = new File(tempPath).getParentFile();
        if (zoneDir == null) return null;

        File[] tripTemps = zoneDir.listFiles(
            (d, name) -> name.startsWith("trip_point_") && name.endsWith("_temp"));
        if (tripTemps == null || tripTemps.length == 0) return null;

        Integer amber = null, red = null;
        for (File tripTemp : tripTemps) {
            Integer raw = readIntFromLine(tripTemp.getPath());
            if (raw == null) continue;
            int celsius = raw > 1000 ? (raw + 500) / 1000 : raw;
            if (celsius < 20 || celsius > 120) continue;

            String typePath = tripTemp.getPath().replaceAll("_temp$", "_type");
            String type = readFirstLine(typePath);
            if (type == null) continue;
            type = type.trim().toLowerCase(Locale.US);

            if (type.contains("critical") || type.contains("hot")) {
                if (red == null || celsius < red) red = celsius;
            } else if (type.contains("passive")) {
                if (amber == null || celsius < amber) amber = celsius;
            }
        }
        if (red == null) return null;
        if (amber == null || amber >= red) amber = amberForRed(red);
        return new Thresholds(amber, red);
    }

    public static int tempColor(Float celsius, Thresholds t, TempDisplay display, int fallbackColor) {
        if (celsius == null || t == null || display == null || !display.colorBands) return fallbackColor;
        if (celsius >= t.redC) return TEMP_COLOR_RED;
        if (celsius >= t.amberC) return TEMP_COLOR_AMBER;
        return TEMP_COLOR_OK;
    }

    public static String formatTemp(float celsius, TempDisplay display, boolean oneDecimal) {
        boolean f = display != null && display.fahrenheit;
        float value = f ? celsius * 9f / 5f + 32f : celsius;
        String unit = f ? "°F" : "°C";
        return oneDecimal
            ? String.format(Locale.ENGLISH, "%.1f%s", value, unit)
            : String.format(Locale.ENGLISH, "%d%s", Math.round(value), unit);
    }

    public static final class Battery {
        public final float watts;
        public final boolean charging;
        public final Integer percent;
        public final Integer tempC;
        public final String runtimeText;

        Battery(float watts, boolean charging) {
            this(watts, charging, null, null, null);
        }
        Battery(float watts, boolean charging, Integer percent, Integer tempC, String runtimeText) {
            this.watts = watts;
            this.charging = charging;
            this.percent = percent;
            this.tempC = tempC;
            this.runtimeText = runtimeText;
        }
    }

    private static final String[] CURRENT_CHANNELS = {
        "/sys/class/power_supply/battery/current_now",
        "/sys/class/power_supply/bms/current_now",
        "/sys/class/power_supply/main/current_now",
    };

    private static final String[] VOLTAGE_CHANNELS = {
        "/sys/class/power_supply/battery/voltage_now",
        "/sys/class/power_supply/bms/voltage_now",
        "/sys/class/power_supply/main/voltage_now",
    };
    private static final String[] POWER_CHANNELS = {
        "/sys/class/power_supply/battery/power_now",
        "/sys/class/power_supply/bms/power_now",
        "/sys/class/power_supply/main/power_now",
    };

    private static Long readLongFromChannels(String[] paths) {
        for (String p : paths) {
            Long v = readLongFromLine(p);
            if (v != null) return v;
        }
        return null;
    }

    private static Boolean readChargingFromPowerSupply() {
        File[] supplies = new File("/sys/class/power_supply").listFiles();
        if (supplies == null || supplies.length == 0) return null;
        Boolean sawSignal = null;
        for (File supply : supplies) {
            String name = supply.getName().toLowerCase(Locale.US);
            String type = readFirstLine(new File(supply, "type").getPath());
            type = type == null ? "" : type.trim().toLowerCase(Locale.US);
            boolean isBattery = type.equals("battery") || name.equals("battery") || name.equals("bms");
            if (isBattery) {
                String st = readFirstLine(new File(supply, "status").getPath());
                if (st == null) continue;
                st = st.trim().toLowerCase(Locale.US);
                sawSignal = Boolean.FALSE;
                if (st.equals("charging") || st.equals("full")) return Boolean.TRUE;
            } else {
                Long online = readLongFromLine(new File(supply, "online").getPath());
                if (online == null) continue;
                sawSignal = Boolean.FALSE;
                if (online == 1L) return Boolean.TRUE;
            }
        }
        return sawSignal;
    }

    private Double smoothedBatteryRuntimeHours = null;
    private static final double MAX_RUNTIME_HOURS = 72.0;
    private static final double RUNTIME_SMOOTHING_OLD_WEIGHT = 0.65;
    private static final double RUNTIME_SMOOTHING_NEW_WEIGHT = 0.35;

    public Battery collectBattery() {
        BatteryManager bm = (BatteryManager) context.getSystemService(Context.BATTERY_SERVICE);
        if (bm == null) return new Battery(0f, false);

        Integer percent = null;
        int cap = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
        if (cap >= 0 && cap <= 100) percent = cap;

        Intent status = context.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (status == null) {
            Boolean sysCharging = readChargingFromPowerSupply();
            return new Battery(0f, sysCharging != null && sysCharging, percent, null, null);
        }

        int st = status.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN);
        boolean charging = st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL;
        if (st == BatteryManager.BATTERY_STATUS_UNKNOWN) {
            Boolean sysCharging = readChargingFromPowerSupply();
            if (sysCharging != null) charging = sysCharging;
        }
        long currentMicroAmps = Math.abs(bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW));
        long chargeCounter = bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER);
        int voltageMv = status.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0);

        Integer tempC = null;
        int rawTemp = status.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0);
        if (rawTemp > 0) tempC = Math.round(rawTemp / 10f);

        if (voltageMv <= 0) {
            Long uv = readLongFromChannels(VOLTAGE_CHANNELS);
            if (uv != null && uv > 0) voltageMv = (int) (Math.abs(uv) / 1000L);
        }
        float amps = batteryCurrentAmps(bm);
        float watts = 0f;
        if (amps > 0f && voltageMv > 0) {
            watts = (voltageMv / 1000f) * amps;
        } else {
            Long uw = readLongFromChannels(POWER_CHANNELS);
            if (uw != null && uw != 0L) watts = (float) (Math.abs(uw) / 1_000_000.0);
        }

        String runtimeText;
        if (charging) {
            smoothedBatteryRuntimeHours = null;
            runtimeText = "LEFT CHG";
        } else if (currentMicroAmps <= 0L || chargeCounter <= 0L) {
            runtimeText = null;
        } else {
            double rawHours = (double) chargeCounter / (double) currentMicroAmps;
            if (!Double.isFinite(rawHours) || rawHours <= 0.0 || rawHours > MAX_RUNTIME_HOURS) {
                runtimeText = null;
            } else {
                double smoothed = smoothedBatteryRuntimeHours == null ? rawHours
                    : (smoothedBatteryRuntimeHours * RUNTIME_SMOOTHING_OLD_WEIGHT)
                        + (rawHours * RUNTIME_SMOOTHING_NEW_WEIGHT);
                smoothedBatteryRuntimeHours = smoothed;
                runtimeText = "LEFT " + formatRuntimeHours(smoothed);
            }
        }
        return new Battery(watts, charging, percent, tempC, runtimeText);
    }

    private static String formatRuntimeHours(double hours) {
        int totalMinutes = Math.max(1, (int) Math.round(hours * 60.0));
        int wholeHours = totalMinutes / 60;
        int minutes = totalMinutes % 60;
        if (wholeHours > 0 && minutes > 0) return wholeHours + "h " + minutes + "m";
        if (wholeHours > 0) return wholeHours + "h";
        return minutes + "m";
    }

    private static float currentRawToAmps(long raw) {
        if (raw == 0L || raw == Long.MIN_VALUE) return 0f;
        long a = Math.abs(raw);
        return a < 20000L ? a / 1000f : a / 1_000_000f;
    }

    private float batteryCurrentAmps(BatteryManager bm) {
        long raw = (bm != null) ? bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW) : 0L;
        if (raw == 0L || raw == Long.MIN_VALUE) {
            Long sys = readLongFromChannels(CURRENT_CHANNELS);
            raw = (sys != null) ? sys : 0L;
        }
        return currentRawToAmps(raw);
    }

    public Integer getCpuClockMhz() {
        int cores = Runtime.getRuntime().availableProcessors();
        long maxKhz = 0;
        for (int i = 0; i < cores; i++) {
            Long cur = readLongFromLine("/sys/devices/system/cpu/cpu" + i + "/cpufreq/scaling_cur_freq");
            if (cur != null && cur > maxKhz) maxKhz = cur;
        }
        if (maxKhz <= 0) return null;
        return (int) (maxKhz / 1000L);
    }

    private List<String> gpuClockPathsCache = null;
    private static final String[] GPU_CLOCK_PATHS = {
        "/sys/class/kgsl/kgsl-3d0/gpuclk",
        "/sys/class/kgsl/kgsl-3d0/devfreq/cur_freq",
        "/sys/class/kgsl/kgsl-3d0/clock_mhz",
        "/sys/kernel/gpu/gpu_clock",
        "/sys/devices/platform/gpusysfs/gpu_clock",
        "/sys/class/misc/mali0/device/clock",
        "/sys/class/devfreq/gpu/cur_freq",
        "/sys/kernel/ged/hal/current_freqency",
        "/sys/kernel/ged/hal/gpu_clock",
    };

    private List<String> discoverGpuClockPaths() {
        if (gpuClockPathsCache != null) return gpuClockPathsCache;
        LinkedHashSet<String> found = new LinkedHashSet<>();
        for (String p : GPU_CLOCK_PATHS) if (new File(p).canRead()) found.add(p);
        File[] devfreqRoots = {
            new File("/sys/class/devfreq"),
            new File("/sys/devices/virtual/devfreq"),
        };
        for (File root : devfreqRoots) {
            if (!root.isDirectory()) continue;
            File[] nodeDirs = root.listFiles(File::isDirectory);
            if (nodeDirs == null) continue;
            for (File node : nodeDirs) {
                String nodePath = node.getPath().toLowerCase(Locale.US);
                boolean looksLikeGpu = false;
                for (String t : GPU_NODE_TOKENS) {
                    if (nodePath.contains(t)) { looksLikeGpu = true; break; }
                }
                if (!looksLikeGpu) continue;
                File f = new File(node, "cur_freq");
                if (f.canRead()) found.add(f.getPath());
            }
        }
        gpuClockPathsCache = new ArrayList<>(found);
        return gpuClockPathsCache;
    }

    public Integer getGpuClockMhz() {
        for (String p : discoverGpuClockPaths()) {
            Long raw = readLongFromLine(p);
            if (raw == null || raw <= 0) continue;
            long mhz;
            if (raw >= 1_000_000L) mhz = raw / 1_000_000L;
            else if (raw >= 10_000L) mhz = raw / 1_000L;
            else mhz = raw;
            if (mhz >= 1 && mhz <= 3000) return (int) mhz;
        }
        return null;
    }

    private List<String> vramUsedPathsCache = null;
    private static final String[] VRAM_USED_PATHS = {
        "/sys/class/kgsl/kgsl-3d0/gpumem_mapped",
        "/sys/class/kgsl/kgsl-3d0/page_alloc",
        "/sys/class/kgsl/kgsl-3d0/mapped",
        "/sys/class/drm/card0/device/mem_info_vram_used",
        "/sys/class/kgsl/kgsl-3d0/page_alloc_max",
    };
    private static final String[] VRAM_TOTAL_PATHS = {
        "/sys/class/drm/card0/device/mem_info_vram_total",
    };

    public Long getVramUsedBytes() {
        if (vramUsedPathsCache == null) {
            ArrayList<String> found = new ArrayList<>();
            for (String p : VRAM_USED_PATHS) if (new File(p).canRead()) found.add(p);
            vramUsedPathsCache = found;
        }
        for (String p : vramUsedPathsCache) {
            Long b = sanitizeVramBytes(readLongFromLine(p));
            if (b != null) return b;
        }
        return null;
    }

    private static Long sanitizeVramBytes(Long v) {
        if (v == null) return null;
        if (v < (1L << 20)) return null;
        if (v > 64L * (1L << 30)) return null;
        return v;
    }

    private static String formatGiB(long bytes) {
        return String.format(Locale.US, "%.1fGiB", bytes / (1024.0 * 1024.0 * 1024.0));
    }

    public static final class Snapshot {
        public final Integer cpuPercent;
        public final Integer gpuPercent;
        public final Integer cpuClockMhz;
        public final Integer gpuClockMhz;
        public final Integer cpuTempC;
        public final Integer gpuTempC;
        public final long ramUsedBytes;
        public final long ramTotalBytes;
        public final float ramPercent;
        public final Long vramUsedBytes;
        public final Battery battery;
        public final int[] perCorePercent;
        public final int[] perCoreClockMhz;
        public final Long swapUsedBytes;
        public final Long swapTotalBytes;
        public final Long netDownBps;
        public final Long netUpBps;

        Snapshot(Integer cpuPercent, Integer gpuPercent, Integer cpuClockMhz, Integer gpuClockMhz,
                 Integer cpuTempC, Integer gpuTempC, long ramUsedBytes, long ramTotalBytes,
                 float ramPercent, Long vramUsedBytes, Battery battery,
                 int[] perCorePercent, int[] perCoreClockMhz, Long swapUsedBytes, Long swapTotalBytes,
                 Long netDownBps, Long netUpBps) {
            this.cpuPercent = cpuPercent;
            this.gpuPercent = gpuPercent;
            this.cpuClockMhz = cpuClockMhz;
            this.gpuClockMhz = gpuClockMhz;
            this.cpuTempC = cpuTempC;
            this.gpuTempC = gpuTempC;
            this.ramUsedBytes = ramUsedBytes;
            this.ramTotalBytes = ramTotalBytes;
            this.ramPercent = ramPercent;
            this.vramUsedBytes = vramUsedBytes;
            this.battery = battery;
            this.perCorePercent = perCorePercent != null ? perCorePercent : new int[0];
            this.perCoreClockMhz = perCoreClockMhz != null ? perCoreClockMhz : new int[0];
            this.swapUsedBytes = swapUsedBytes;
            this.swapTotalBytes = swapTotalBytes;
            this.netDownBps = netDownBps;
            this.netUpBps = netUpBps;
        }

        public String ramUsedText() { return formatBytesGb(ramUsedBytes); }
        public String ramTotalText() { return formatBytesGb(ramTotalBytes); }
        public String vramText() { return vramUsedBytes == null ? null : formatGiB(vramUsedBytes); }
        public String swapUsedText() { return swapUsedBytes == null ? null : formatBytesGb(swapUsedBytes); }
        public String swapTotalText() { return swapTotalBytes == null ? null : formatBytesGb(swapTotalBytes); }

        private static String formatBytesGb(long bytes) {
            return String.format(Locale.US, "%.1fGiB", bytes / (1024.0 * 1024.0 * 1024.0));
        }
    }

    public Snapshot snapshot() {
        if (mango != null) return mangoSnapshot(mango);
        Integer cpu = getCpuUsagePercent();
        Integer gpu = getGpuUsagePercent();
        Integer cpuClk = getCpuClockMhz();
        Integer gpuClk = getGpuClockMhz();
        Integer cpuTemp = getCpuTempC();
        Integer gpuTemp = getGpuTempC();

        long usedBytes = 0, totalBytes = 0;
        float ramPct = 0f;
        ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        if (am != null) {
            ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
            am.getMemoryInfo(mi);
            totalBytes = Math.max(0L, mi.totalMem);
            usedBytes = Math.max(0L, mi.totalMem - mi.availMem);
            ramPct = totalBytes > 0 ? usedBytes * 100f / totalBytes : 0f;
        }

        Long vram = getVramUsedBytes();
        Battery battery = collectBattery();

        int[] perCorePct = readPerCorePercent();
        int[] perCoreClk = getPerCoreClockMhz();
        long[] swap = getSwapBytes();
        long[] net = readNetRateBps();

        return new Snapshot(cpu, gpu, cpuClk, gpuClk, cpuTemp, gpuTemp,
            usedBytes, totalBytes, ramPct, vram, battery,
            perCorePct, perCoreClk,
            swap == null ? null : swap[0], swap == null ? null : swap[1],
            net == null ? null : net[0], net == null ? null : net[1]);
    }

    private Snapshot mangoSnapshot(MangoReadings m) {
        MangoReadings.Cpu cpu = m.cpu();
        MangoReadings.Memory mem = m.memory();
        long used = mem != null ? mem.getUsedBytes() : 0L;
        long total = mem != null ? mem.getTotalBytes() : 0L;
        Battery android = collectBattery();
        MangoReadings.Battery bat = m.battery();
        Battery battery = bat == null ? android : new Battery(bat.getWatts(), bat.getCharging(),
            bat.getPercent() != null ? bat.getPercent() : android.percent, android.tempC, android.runtimeText);
        long[] net = readNetRateBps();
        return new Snapshot(cpu != null ? cpu.getPercent() : null, m.gpuPercent(), getCpuClockMhz(), m.gpuClockMhz(),
            m.cpuTempC(), m.gpuTempC(), used, total, total > 0 ? used * 100f / total : 0f, m.vramBytes(), battery,
            cpu != null ? cpu.getPerCore() : new int[0], getPerCoreClockMhz(),
            mem != null && mem.getSwapTotalBytes() > 0 ? mem.getSwapUsedBytes() : null,
            mem != null && mem.getSwapTotalBytes() > 0 ? mem.getSwapTotalBytes() : null,
            net == null ? null : net[0], net == null ? null : net[1]);
    }

    public int[] getPerCoreClockMhz() {
        int cores = Runtime.getRuntime().availableProcessors();
        int[] out = new int[Math.max(0, cores)];
        for (int i = 0; i < out.length; i++) {
            Long cur = readLongFromLine("/sys/devices/system/cpu/cpu" + i + "/cpufreq/scaling_cur_freq");
            out[i] = (cur != null && cur > 0) ? (int) (cur / 1000L) : 0;
        }
        return out;
    }

    private long[] lastCoreTotal = null;
    private long[] lastCoreIdle = null;

    public int[] readPerCorePercent() {
        int cores = Runtime.getRuntime().availableProcessors();
        int[] out = new int[Math.max(0, cores)];
        java.util.Arrays.fill(out, -1);
        if (lastCoreTotal == null || lastCoreTotal.length != cores) {
            lastCoreTotal = new long[cores];
            lastCoreIdle = new long[cores];
            java.util.Arrays.fill(lastCoreTotal, -1L);
        }
        try (BufferedReader r = new BufferedReader(new FileReader("/proc/stat"))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (!line.startsWith("cpu")) break;
                if (line.length() < 4 || !Character.isDigit(line.charAt(3))) continue;
                String[] parts = line.trim().split("\\s+");
                int core;
                try { core = Integer.parseInt(parts[0].substring(3)); } catch (Exception e) { continue; }
                if (core < 0 || core >= cores) continue;
                long total = 0, idle = 0;
                boolean ok = true;
                for (int i = 1; i < parts.length; i++) {
                    try {
                        long v = Long.parseLong(parts[i]);
                        total += v;
                        if (i == 4) idle = v;
                        else if (i == 5) idle += v;
                    } catch (NumberFormatException e) { ok = false; break; }
                }
                if (!ok) continue;
                long prevTotal = lastCoreTotal[core], prevIdle = lastCoreIdle[core];
                lastCoreTotal[core] = total;
                lastCoreIdle[core] = idle;
                if (prevTotal >= 0) {
                    long dTotal = total - prevTotal, dIdle = idle - prevIdle;
                    if (dTotal > 0) out[core] = clampPercent((int) ((Math.max(0, dTotal - dIdle) * 100L) / dTotal));
                }
            }
        } catch (Exception ignored) {}
        for (int i = 0; i < out.length; i++) {
            if (out[i] >= 0) continue;
            Long cur = readLongFromLine("/sys/devices/system/cpu/cpu" + i + "/cpufreq/scaling_cur_freq");
            Long max = readLongFromLine("/sys/devices/system/cpu/cpu" + i + "/cpufreq/cpuinfo_max_freq");
            if (cur != null && max != null && max > 0L)
                out[i] = clampPercent((int) ((Math.max(0L, Math.min(cur, max)) * 100L) / max));
        }
        return out;
    }

    public long[] getSwapBytes() {
        long totalKb = -1, freeKb = -1;
        try (BufferedReader r = new BufferedReader(new FileReader("/proc/meminfo"))) {
            String line;
            while ((line = r.readLine()) != null && (totalKb < 0 || freeKb < 0)) {
                if (line.startsWith("SwapTotal:")) totalKb = parseMeminfoKb(line);
                else if (line.startsWith("SwapFree:")) freeKb = parseMeminfoKb(line);
            }
        } catch (Exception e) { return null; }
        if (totalKb <= 0) return null;
        long used = Math.max(0L, (totalKb - Math.max(0L, freeKb))) * 1024L;
        return new long[]{used, totalKb * 1024L};
    }

    private static long parseMeminfoKb(String line) {
        String[] parts = line.trim().split("\\s+");
        if (parts.length < 2) return -1;
        Long v = parseLong(parts[1]);
        return v == null ? -1 : v;
    }

    private long lastNetRx = -1, lastNetTx = -1, lastNetWallMs = 0;

    public long[] readNetRateBps() {
        long rx = 0, tx = 0;
        boolean any = false;
        try (BufferedReader r = new BufferedReader(new FileReader("/proc/net/dev"))) {
            String line;
            while ((line = r.readLine()) != null) {
                int colon = line.indexOf(':');
                if (colon < 0) continue;
                String iface = line.substring(0, colon).trim();
                if (iface.equals("lo") || iface.isEmpty()) continue;
                String[] f = line.substring(colon + 1).trim().split("\\s+");
                if (f.length < 9) continue;
                Long rxB = parseLong(f[0]);
                Long txB = parseLong(f[8]);
                if (rxB == null || txB == null) continue;
                rx += rxB; tx += txB; any = true;
            }
        } catch (Exception e) { return null; }
        if (!any) return null;
        long now = SystemClock.elapsedRealtime();
        long prevRx = lastNetRx, prevTx = lastNetTx, prevWall = lastNetWallMs;
        lastNetRx = rx; lastNetTx = tx; lastNetWallMs = now;
        if (prevRx < 0 || prevWall <= 0) return null;
        long dt = now - prevWall;
        if (dt <= 0) return null;
        long down = Math.max(0L, (rx - prevRx) * 1000L / dt);
        long up = Math.max(0L, (tx - prevTx) * 1000L / dt);
        return new long[]{down, up};
    }

    public String buildDiagnosticsReport(Context ctxIn) {
        final Context ctx = ctxIn != null ? ctxIn : this.context;
        snapshot();
        try { Thread.sleep(350L); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        Snapshot s = snapshot();

        StringBuilder sb = new StringBuilder();
        sb.append("DroidDeck HUD diagnostics\n");
        sb.append("Generated: ")
          .append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date())).append('\n');
        sb.append("Device: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n');
        sb.append("Board/HW: ").append(Build.BOARD).append(" / ").append(Build.HARDWARE).append('\n');
        sb.append("SoC: ").append(socString()).append('\n');
        sb.append("Android: ").append(Build.VERSION.RELEASE)
          .append(" (API ").append(Build.VERSION.SDK_INT).append(")\n");
        sb.append("CPU cores: ").append(Runtime.getRuntime().availableProcessors()).append('\n');
        sb.append("GPU renderer: ").append(safeGpu(() -> com.droiddeck.launcher.gpu.GpuInfo.Companion.detect().displayName(ctx))).append('\n');

        sb.append("\n== Metrics ==\n");

        boolean procStat = new File("/proc/stat").canRead();
        if (s.cpuPercent != null) {
            metricHit(sb, "CPU %", s.cpuPercent + "%",
                procStat ? "/proc/stat (cpu aggregate delta)"
                         : "/sys/devices/system/cpu/cpu*/cpufreq/scaling_cur_freq (freq estimate)");
        } else {
            metricMiss(sb, "CPU %", "/proc/stat",
                "/sys/devices/system/cpu/cpu*/cpufreq/scaling_cur_freq");
        }

        StringBuilder cores = new StringBuilder();
        for (int i = 0; i < s.perCorePercent.length; i++) {
            if (i > 0) cores.append(' ');
            int v = s.perCorePercent[i];
            cores.append('c').append(i).append('=').append(v < 0 ? "?" : (v + "%"));
        }
        if (s.perCorePercent.length > 0)
            metricHit(sb, "Per-core %", cores.toString(), "/proc/stat (per-cpu lines)");
        else
            metricMiss(sb, "Per-core %", "/proc/stat");

        StringBuilder coreClk = new StringBuilder();
        for (int i = 0; i < s.perCoreClockMhz.length; i++) {
            if (i > 0) coreClk.append(' ');
            int v = s.perCoreClockMhz[i];
            coreClk.append('c').append(i).append('=').append(v <= 0 ? "?" : (v + "MHz"));
        }
        if (s.cpuClockMhz != null)
            metricHit(sb, "CPU clock", s.cpuClockMhz + "MHz (peak); " + coreClk,
                "/sys/devices/system/cpu/cpu*/cpufreq/scaling_cur_freq");
        else
            metricMiss(sb, "CPU clock", "/sys/devices/system/cpu/cpu*/cpufreq/scaling_cur_freq");

        String cpuTempPath = resolveTempPath(discoverPrioritizedCpuTempPaths());
        if (s.cpuTempC != null && cpuTempPath != null)
            metricHit(sb, "CPU temp", s.cpuTempC + "°C", cpuTempPath);
        else
            metricMiss(sb, "CPU temp",
                "/sys/class/thermal/thermal_zone*/temp (types: cpu*/cpuss/tsens/mtktscpu/s5p-tmu/soc)");

        String gpuUsagePath = firstReadableGpuUsagePath();
        if (s.gpuPercent != null && gpuUsagePath != null)
            metricHit(sb, "GPU %", s.gpuPercent + "%", gpuUsagePath);
        else
            metricMissList(sb, "GPU %", discoverGpuUsagePaths(), GPU_USAGE_STATIC_PATHS);

        String gpuClockPath = firstReadableGpuClockPath();
        if (s.gpuClockMhz != null && gpuClockPath != null)
            metricHit(sb, "GPU clock", s.gpuClockMhz + "MHz", gpuClockPath);
        else
            metricMissList(sb, "GPU clock", discoverGpuClockPaths(), GPU_CLOCK_PATHS);

        String gpuTempPath = resolveTempPath(discoverPrioritizedGpuTempPaths());
        if (s.gpuTempC != null)
            metricHit(sb, "GPU temp", s.gpuTempC + "°C",
                gpuTempPath != null ? gpuTempPath : "kgsl/mali direct temp node");
        else
            metricMiss(sb, "GPU temp",
                "/sys/class/kgsl/kgsl-3d0/temp", "/sys/class/misc/mali0/device/temp",
                "/sys/class/thermal/thermal_zone*/temp (types: gpu*/gpuss/g3d/kgsl/mali/mtktsgpu)");

        metricHit(sb, "RAM", s.ramUsedText() + " / " + s.ramTotalText()
                + " (" + Math.round(s.ramPercent) + "%)", "ActivityManager.MemoryInfo");
        if (s.swapUsedBytes != null)
            metricHit(sb, "SWAP", s.swapUsedText() + " / " + s.swapTotalText(), "/proc/meminfo");
        else
            metricMiss(sb, "SWAP", "/proc/meminfo (SwapTotal=0 or unreadable)");

        String vramPath = firstReadableVramUsedPath();
        if (s.vramUsedBytes != null && vramPath != null) {
            Long total = readFirstReadableLong(VRAM_TOTAL_PATHS);
            String v = s.vramText() + (total != null ? " / " + formatGiB(total) : "");
            metricHit(sb, "VRAM", v, vramPath);
        } else {
            metricMissList(sb, "VRAM (Java sysfs; Vulkan budget = native TODO)",
                new ArrayList<>(), VRAM_USED_PATHS);
        }

        if (s.netDownBps != null)
            metricHit(sb, "NET", formatRate(s.netDownBps) + " down / " + formatRate(s.netUpBps) + " up",
                "/proc/net/dev");
        else
            metricMiss(sb, "NET", "/proc/net/dev (first sample or unreadable)");

        Battery b = s.battery;
        StringBuilder bat = new StringBuilder();
        bat.append(b.percent != null ? b.percent + "%" : "?%")
           .append(", ").append(String.format(Locale.US, "%.1fW", b.watts))
           .append(b.charging ? " (charging)" : "")
           .append(", temp ").append(b.tempC != null ? b.tempC + "°C" : "?")
           .append(b.runtimeText != null ? ", " + b.runtimeText : "");
        metricHit(sb, "Battery", bat.toString(), "BatteryManager + /sys/class/power_supply");

        appendExistsDump(sb);
        return sb.toString();
    }

    private static String socString() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return Build.SOC_MANUFACTURER + " " + Build.SOC_MODEL;
        }
        return "(SoC id needs Android 12+; HW=" + Build.HARDWARE + ")";
    }

    private interface GpuStr { String get(); }
    private static String safeGpu(GpuStr fn) {
        try { String v = fn.get(); return v == null ? "(null)" : v; }
        catch (Throwable t) { return "(unavailable: " + t.getClass().getSimpleName() + ")"; }
    }

    private String firstReadableGpuUsagePath() {
        for (String p : discoverGpuUsagePaths()) if (readGpuUsageSample(p) != null) return p;
        return null;
    }

    private String firstReadableGpuClockPath() {
        for (String p : discoverGpuClockPaths()) {
            Long raw = readLongFromLine(p);
            if (raw != null && raw > 0) return p;
        }
        return null;
    }

    private String firstReadableVramUsedPath() {
        if (vramUsedPathsCache == null) getVramUsedBytes();
        if (vramUsedPathsCache == null) return null;
        for (String p : vramUsedPathsCache) if (sanitizeVramBytes(readLongFromLine(p)) != null) return p;
        return null;
    }

    private static Long readFirstReadableLong(String[] paths) {
        for (String p : paths) {
            Long v = readLongFromLine(p);
            if (v != null && v > 0) return v;
        }
        return null;
    }

    private static String formatRate(Long bps) {
        if (bps == null) return "?";
        double kb = bps / 1024.0;
        if (kb >= 1024.0) return String.format(Locale.US, "%.1fMB/s", kb / 1024.0);
        return String.format(Locale.US, "%.0fKB/s", kb);
    }

    private static void metricHit(StringBuilder sb, String name, String value, String source) {
        sb.append(name).append(": ").append(value).append("\n    source: ").append(source).append('\n');
    }

    private static void metricMiss(StringBuilder sb, String name, String... candidates) {
        sb.append(name).append(": MISS\n    tried: ");
        for (int i = 0; i < candidates.length; i++) {
            if (i > 0) sb.append("\n           ");
            sb.append(candidates[i]);
        }
        sb.append('\n');
    }

    private static void metricMissList(StringBuilder sb, String name, List<String> discovered, String[] statics) {
        LinkedHashSet<String> all = new LinkedHashSet<>();
        if (discovered != null) all.addAll(discovered);
        for (String p : statics) all.add(p);
        sb.append(name).append(": MISS\n    tried: ");
        boolean first = true;
        for (String p : all) {
            if (!first) sb.append("\n           ");
            sb.append(p);
            first = false;
        }
        if (first) sb.append("(no candidates)");
        sb.append('\n');
    }

    private void appendExistsDump(StringBuilder sb) {
        sb.append("\n== Exists on this device (send these back for any MISS above) ==\n");
        sb.append("-- /sys/class/thermal/thermal_zone*/type --\n");
        List<String[]> zones = discoverAllThermalZones();
        if (zones.isEmpty()) sb.append("  (none readable)\n");
        for (String[] z : zones) sb.append("  ").append(z[1]).append("  type=").append(z[0]).append('\n');
        appendDevfreqListing(sb, "/sys/class/devfreq");
        appendDevfreqListing(sb, "/sys/devices/virtual/devfreq");
        appendReadableFiles(sb, "/sys/class/kgsl/kgsl-3d0");
        appendReadableFiles(sb, "/sys/class/misc/mali0/device");
        appendReadableFiles(sb, "/sys/class/drm/card0/device");
        appendPowerSupplyDump(sb);
        appendGpuNoRootProbes(sb);
    }

    private void appendGpuNoRootProbes(StringBuilder sb) {
        sb.append("-- GPU no-root reachability probes --\n");
        sb.append("  /dev/kgsl-3d0 open O_RDWR:   ")
          .append(probeOpen("/dev/kgsl-3d0", android.system.OsConstants.O_RDWR)).append('\n');
        sb.append("  /dev/kgsl-3d0 open O_RDONLY: ")
          .append(probeOpen("/dev/kgsl-3d0", android.system.OsConstants.O_RDONLY)).append('\n');
        sb.append("  /dev/dri/renderD128 O_RDWR:  ")
          .append(probeOpen("/dev/dri/renderD128", android.system.OsConstants.O_RDWR)).append('\n');
        boolean found = false;
        File[] roots = { new File("/sys/devices/platform/soc"), new File("/sys/devices/platform") };
        for (File root : roots) {
            File[] nodes = root.listFiles();
            if (nodes == null) continue;
            for (File soc : nodes) {
                if (!soc.getName().contains("kgsl-3d0")) continue;
                File[] inner = new File(soc, "devfreq").listFiles();
                if (inner == null) continue;
                for (File node : inner) {
                    found = true;
                    Long cur = readLongFromLine(node + "/cur_freq");
                    Long load = readLongFromLine(node + "/gpu_load");
                    if (load == null) load = readLongFromLine(node + "/load");
                    sb.append("  devfreq ").append(node.getAbsolutePath())
                      .append(": cur_freq=").append(cur == null ? "—" : cur.toString())
                      .append(" load=").append(load == null ? "—" : load.toString()).append('\n');
                }
            }
        }
        if (!found) sb.append("  devfreq (platform kgsl): (no app-readable node found)\n");
    }

    private static String probeOpen(String path, int flags) {
        java.io.FileDescriptor fd = null;
        try {
            fd = android.system.Os.open(path, flags, 0);
            return "OK";
        } catch (Throwable t) {
            return t.getClass().getSimpleName() + ": " + t.getMessage();
        } finally {
            if (fd != null) try { android.system.Os.close(fd); } catch (Throwable ignored) {}
        }
    }

    private void appendPowerSupplyDump(StringBuilder sb) {
        sb.append("-- /sys/class/power_supply (battery watts inputs) --\n");
        File[] nodes = new File("/sys/class/power_supply").listFiles();
        if (nodes == null || nodes.length == 0) { sb.append("  (absent / unreadable)\n"); return; }
        boolean any = false;
        for (File node : nodes) {
            Long v = readLongFromLine(node + "/voltage_now");
            Long c = readLongFromLine(node + "/current_now");
            Long p = readLongFromLine(node + "/power_now");
            if (v == null && c == null && p == null) continue;
            any = true;
            sb.append("  ").append(node.getName())
              .append(": voltage_now=").append(v == null ? "—" : v + "µV")
              .append(" current_now=").append(c == null ? "—" : c + "µA")
              .append(" power_now=").append(p == null ? "—" : p + "µW")
              .append('\n');
        }
        if (!any) sb.append("  (nodes present but voltage_now/current_now/power_now unreadable)\n");
    }

    private static void appendDevfreqListing(StringBuilder sb, String rootPath) {
        File root = new File(rootPath);
        File[] nodes = root.listFiles(File::isDirectory);
        sb.append("-- ").append(rootPath).append(" --\n");
        if (nodes == null || nodes.length == 0) { sb.append("  (absent / empty)\n"); return; }
        for (File node : nodes) {
            sb.append("  ").append(node.getName()).append("/: ").append(readableFileNames(node)).append('\n');
        }
    }

    private static void appendReadableFiles(StringBuilder sb, String dirPath) {
        File dir = new File(dirPath);
        sb.append("-- ").append(dirPath).append(" --\n");
        if (!dir.isDirectory()) { sb.append("  (absent / unreadable)\n"); return; }
        sb.append("  ").append(readableFileNames(dir)).append('\n');
    }

    private static String readableFileNames(File dir) {
        File[] files = dir.listFiles();
        if (files == null) return "(unreadable)";
        StringBuilder names = new StringBuilder();
        for (File f : files) {
            if (f.isFile() && f.canRead()) {
                if (names.length() > 0) names.append(", ");
                names.append(f.getName());
            }
        }
        return names.length() == 0 ? "(no readable files)" : names.toString();
    }

    private static int clampPercent(int v) { return v < 0 ? 0 : (v > 100 ? 100 : v); }

    private static String readFirstLine(String path) {
        try (BufferedReader r = new BufferedReader(new FileReader(path))) {
            return r.readLine();
        } catch (Exception e) {
            return null;
        }
    }

    private static String readNthLine(String path, int lineIndex) {
        try (BufferedReader r = new BufferedReader(new FileReader(path))) {
            String line = null;
            for (int i = 0; i <= lineIndex; i++) {
                line = r.readLine();
                if (line == null) return null;
            }
            return line;
        } catch (Exception e) {
            return null;
        }
    }

    private static Long readLongFromLine(String path) {
        String line = readFirstLine(path);
        return line == null ? null : parseLong(line.trim());
    }

    private static Integer readIntFromLine(String path) {
        String line = readFirstLine(path);
        return line == null ? null : parseInt(line.trim());
    }

    private static Long parseLong(String s) {
        try { return Long.parseLong(s); } catch (Exception e) { return null; }
    }

    private static Integer parseInt(String s) {
        try { return Integer.parseInt(s); } catch (Exception e) { return null; }
    }
}
