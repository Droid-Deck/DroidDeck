package com.droiddeck.launcher.stores.amazon;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * How an installed Amazon game is started: its `fuel.json` names the exe, a working subfolder and
 * arguments when the publisher shipped one; otherwise the exe is scored (Unreal shipping binaries
 * first, the title's name, never crash handlers or installers). The FuelPump variables the Amazon
 * Games Services SDK reads go into the game's environment.
 */
public final class AmazonLaunchHelper {

    private static final String TAG = "AmazonLaunch";

    public static final class LaunchSpec {
        /** The exe relative to the install folder, forward slashes. */
        public String exeRelative = "";
        /** The arguments fuel.json names, in order. */
        public List<String> args = new ArrayList<>();
    }

    private AmazonLaunchHelper() {}

    public static LaunchSpec buildLaunchSpec(File installDir, String gameTitle) {
        LaunchSpec spec = new LaunchSpec();
        String fuelCommand = null;
        File fuelFile = new File(installDir, "fuel.json");
        if (fuelFile.exists()) {
            try {
                StringBuilder sb = new StringBuilder();
                try (BufferedReader br = new BufferedReader(new FileReader(fuelFile))) {
                    String line;
                    while ((line = br.readLine()) != null) sb.append(line);
                }
                JSONObject main = new JSONObject(sb.toString()).optJSONObject("Main");
                if (main != null) {
                    String cmd = main.optString("Command", "").trim();
                    if (!cmd.isEmpty()) fuelCommand = cmd;
                    JSONArray argsArr = main.optJSONArray("Args");
                    if (argsArr != null) for (int i = 0; i < argsArr.length(); i++) {
                        String a = argsArr.optString(i);
                        if (a != null && !a.isEmpty()) spec.args.add(a);
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "fuel.json unreadable; choosing the exe by name");
            }
        }
        if (fuelCommand != null && new File(installDir, fuelCommand.replace('\\', '/')).isFile()) {
            spec.exeRelative = fuelCommand.replace('\\', '/');
        } else {
            File chosen = choosePrimaryExe(installDir, gameTitle);
            if (chosen != null) spec.exeRelative = relativePath(installDir, chosen);
        }
        return spec;
    }

    /** The FuelPump variables, as KEY=VALUE, for the game's environment. */
    public static String[] buildFuelEnv(String entitlementId, String productSku) {
        return new String[]{
            "FUEL_DIR=C:\\ProgramData\\Amazon Games Services\\Legacy",
            "AMAZON_GAMES_SDK_PATH=C:\\ProgramData\\Amazon Games Services\\AmazonGamesSDK",
            "AMAZON_GAMES_FUEL_ENTITLEMENT_ID=" + entitlementId,
            "AMAZON_GAMES_FUEL_PRODUCT_SKU=" + productSku,
            "AMAZON_GAMES_FUEL_DISPLAY_NAME=Player",
        };
    }

    private static final Pattern UE_SHIPPING = Pattern.compile(".*-win(32|64)(-shipping)?\\.exe$", Pattern.CASE_INSENSITIVE);
    private static final Pattern UE_BINARIES = Pattern.compile(".*/binaries/win(32|64)/.*\\.exe$", Pattern.CASE_INSENSITIVE);
    private static final Pattern GENERIC_NAME = Pattern.compile("^[a-z]\\d{1,3}\\.exe$", Pattern.CASE_INSENSITIVE);
    private static final String[] NEGATIVE_KEYWORDS = { "crash", "handler", "viewer", "compiler", "tool", "setup", "unins", "eac", "launcher", "steam" };

    private static boolean isLikelyStub(File f) {
        String n = f.getName().toLowerCase();
        if (GENERIC_NAME.matcher(n).matches()) return true;
        if (f.length() < 1_000_000L) return true;
        for (String kw : NEGATIVE_KEYWORDS) if (n.contains(kw)) return true;
        return false;
    }

    static int scoreExe(File f, String gameNameLower) {
        int score = 50;
        String path = f.getAbsolutePath().replace('\\', '/').toLowerCase();
        if (UE_SHIPPING.matcher(path).matches()) score += 300;
        if (UE_BINARIES.matcher(path).find()) score += 250;
        String fn = f.getName().toLowerCase();
        String cleanGame = gameNameLower.replaceAll("[^a-z]", "");
        String cleanFile = fn.replaceAll("[^a-z]", "");
        boolean nameMatch = path.contains(gameNameLower) || (cleanGame.length() >= 5 && cleanFile.length() >= 5 && cleanGame.substring(0, 5).equals(cleanFile.substring(0, 5)));
        if (nameMatch) score += 100;
        for (String kw : NEGATIVE_KEYWORDS) if (path.contains(kw)) { score -= 150; break; }
        if (GENERIC_NAME.matcher(fn).matches()) score -= 200;
        return score;
    }

    static File choosePrimaryExe(File installDir, String gameTitle) {
        if (!installDir.isDirectory()) return null;
        List<File> all = new ArrayList<>();
        collectExe(installDir, all);
        if (all.isEmpty()) return null;
        List<File> pool = new ArrayList<>();
        for (File f : all) if (!isLikelyStub(f)) pool.add(f);
        if (pool.isEmpty()) pool = all;
        String lowerTitle = gameTitle.toLowerCase();
        File best = null;
        int bestScore = Integer.MIN_VALUE;
        for (File f : pool) {
            int s = scoreExe(f, lowerTitle);
            if (best == null || s > bestScore || (s == bestScore && f.length() > best.length())) { best = f; bestScore = s; }
        }
        return best;
    }

    private static void collectExe(File dir, List<File> out) {
        File[] entries = dir.listFiles();
        if (entries == null) return;
        for (File f : entries) {
            if (f.isDirectory()) collectExe(f, out);
            else if (f.getName().toLowerCase().endsWith(".exe")) out.add(f);
        }
    }

    private static String relativePath(File base, File absolute) {
        String b = base.getAbsolutePath();
        if (!b.endsWith("/")) b += "/";
        String a = absolute.getAbsolutePath();
        return a.startsWith(b) ? a.substring(b.length()) : absolute.getName();
    }
}
